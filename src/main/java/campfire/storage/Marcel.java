package campfire.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Marcel 1.1.0 content type identification (`Marcel::MimeType.for`), as
/// `ActiveStorage::Blob#extract_content_type` calls it on upload, over the tables in {@link MarcelData}.
///
/// Ported instead of using a MIME library on purpose: the stored `content_type` decides
/// `variable?`/`previewable?` and inline-vs-attachment serving, so it must be what Rails would
/// have stored for the same upload (Marcel's type names, its parent hierarchy, declared and
/// filename types winning only when more specific). The golden vectors check this.
public final class Marcel {
    private Marcel() {}

    public static final String BINARY = "application/octet-stream";

    /// One entry of `Marcel::MAGIC`: `[offset, value, children]`, where offset may be a Range
    /// (`rangeEnd >= 0`). A null value never matches.
    private record Match(int offset, int rangeEnd, byte[] value, List<Match> children) {}

    private record Magic(String type, List<Match> matches) {}

    private static final Map<String, String> EXTENSIONS = new HashMap<>();
    private static final Map<String, List<String>> TYPE_EXTS = new HashMap<>();
    private static final Map<String, List<String>> TYPE_PARENTS = new HashMap<>();
    private static final List<Magic> MAGIC = new ArrayList<>();
    private static final int MAGIC_PREFIX_LENGTH;

    static {
        String section = "";
        for (String line : MarcelData.TABLES.split("\n")) {
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                section = line;
                continue;
            }
            String[] fields = line.split("\t", -1);
            switch (section) {
                case "#E" -> EXTENSIONS.putIfAbsent(fields[0], fields[1]);
                case "#T" -> TYPE_EXTS.put(fields[0], List.of(fields).subList(1, fields.length));
                case "#P" -> TYPE_PARENTS.put(fields[0], List.of(fields).subList(1, fields.length));
                case "#M" -> {
                    var parser = new MatchParser(fields[1]);
                    MAGIC.add(new Magic(fields[0], parser.list()));
                }
                default -> throw new IllegalStateException("bad Marcel table section " + section);
            }
        }
        int reach = 0;
        for (Magic magic : MAGIC) reach = Math.max(reach, reach(magic.matches()));
        MAGIC_PREFIX_LENGTH = reach;
    }

    /// `offset[-end]:hex[(children)]`, comma-separated.
    private static final class MatchParser {
        final String s;
        int pos;

        MatchParser(String s) {
            this.s = s;
        }

        List<Match> list() {
            var matches = new ArrayList<Match>();
            while (pos < s.length() && s.charAt(pos) != ')') {
                matches.add(match());
                if (pos < s.length() && s.charAt(pos) == ',') pos++;
            }
            return List.copyOf(matches);
        }

        Match match() {
            int colon = s.indexOf(':', pos);
            String offsets = s.substring(pos, colon);
            int dash = offsets.indexOf('-');
            int offset = Integer.parseInt(dash < 0 ? offsets : offsets.substring(0, dash));
            int rangeEnd = dash < 0 ? -1 : Integer.parseInt(offsets.substring(dash + 1));
            pos = colon + 1;
            int end = pos;
            while (end < s.length() && "(),".indexOf(s.charAt(end)) < 0) end++;
            String value = s.substring(pos, end);
            pos = end;
            List<Match> children = List.of();
            if (pos < s.length() && s.charAt(pos) == '(') {
                pos++;
                children = list();
                pos++;
            }
            return new Match(offset, rangeEnd, value.equals("!") ? null : HexFormat.of().parseHex(value), children);
        }
    }

    private static int reach(List<Match> matches) {
        int reach = 0;
        for (Match m : matches) {
            int value = m.value() == null ? 0 : m.value().length;
            int own = m.rangeEnd() >= 0 ? m.rangeEnd() + value : m.offset() + value;
            reach = Math.max(reach, Math.max(own, reach(m.children())));
        }
        return reach;
    }

    /// How many leading bytes {@link #byMagic} can look at: identifying the first
    /// `magicPrefixLength()` bytes of a file gives the same answer as identifying all of it.
    public static int magicPrefixLength() {
        return MAGIC_PREFIX_LENGTH;
    }

    /// `Marcel::MimeType.for(io, name:, declared_type:)`: the most specific of the magic-byte type,
    /// the declared type and the filename's type.
    public static String identify(byte[] head, String name, String declaredType) {
        return mostSpecificType(byMagic(head), forDeclaredType(declaredType), name == null ? null : byPath(name));
    }

    /// `Marcel::MimeType.for(extension:)`.
    public static String forExtension(String extension) {
        return mostSpecificType(byExtension(extension));
    }

    /// `Marcel::Magic.by_extension`: case-insensitive, with or without the leading dot.
    public static String byExtension(String extension) {
        if (extension == null) return null;
        String ext = extension.toLowerCase(Locale.ROOT);
        if (ext.startsWith(".")) ext = ext.substring(1);
        return EXTENSIONS.get(ext);
    }

    /// `Marcel::Magic.by_path`: the extension per Ruby's `File.extname`.
    public static String byPath(String path) {
        return byExtension(Filename.extname(path));
    }

    /// `Marcel::Magic.new(type).extensions`, in table order.
    public static List<String> extensions(String contentType) {
        return TYPE_EXTS.getOrDefault(contentType, List.of());
    }

    /// `Marcel::Magic.child?`.
    public static boolean isChild(String child, String parent) {
        if (child.equals(parent)) return true;
        for (String p : TYPE_PARENTS.getOrDefault(child, List.of())) {
            if (isChild(p, parent)) return true;
        }
        return false;
    }

    /// `Marcel::Magic.by_magic`: the first table entry whose matches hit.
    public static String byMagic(byte[] data) {
        for (Magic magic : MAGIC) {
            if (matchesAny(data, magic.matches())) return magic.type().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static boolean matchesAny(byte[] data, List<Match> matches) {
        for (Match m : matches) {
            byte[] value = m.value();
            if (value == null) continue;
            boolean hit;
            if (m.rangeEnd() >= 0) {
                // `io.read(offset.begin); io.read(offset.end - offset.begin + value.bytesize).include?(value)`
                byte[] window = read(data, m.offset(), m.rangeEnd() - m.offset() + value.length);
                hit = window != null && indexOf(window, value) >= 0;
            } else {
                byte[] bytes = read(data, m.offset(), value.length);
                hit = bytes != null && java.util.Arrays.equals(bytes, value);
            }
            if (hit && (m.children().isEmpty() || matchesAny(data, m.children()))) return true;
        }
        return false;
    }

    /// `IO#read(length)` after skipping `offset` bytes: null at EOF, otherwise up to `length` bytes.
    private static byte[] read(byte[] data, int offset, int length) {
        if (length == 0) return new byte[0];
        if (offset >= data.length) return null;
        return java.util.Arrays.copyOfRange(data, offset, Math.min(data.length, offset + length));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (needle.length == 0) return 0;
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    /// Declared types are downcased, stripped of parameters, and ignored when binary.
    static String forDeclaredType(String declared) {
        if (declared == null) return null;
        String lower = declared.toLowerCase(Locale.ROOT);
        int end = 0;
        while (end < lower.length() && ";, \t\n\r\u000b\f".indexOf(lower.charAt(end)) < 0) end++;
        String mediaType = lower.substring(0, end);
        return mediaType.contains("/") && !mediaType.equals(BINARY) ? mediaType : null;
    }

    /// `most_specific_type(*candidates, BINARY)`: later candidates only win when they are
    /// children of the current pick.
    private static String mostSpecificType(String... candidates) {
        var unique = new LinkedHashSet<String>();
        for (String c : candidates) if (c != null) unique.add(c);
        unique.add(BINARY);
        String pick = null;
        for (String candidate : unique) {
            if (pick == null || isChild(candidate, pick)) pick = candidate;
        }
        return pick;
    }
}
