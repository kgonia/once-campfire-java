package campfire.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MediaTypeRegistry;
import org.apache.tika.mime.MimeTypes;
import org.jspecify.annotations.Nullable;

/// The content type Active Storage stores for an upload. Rails asks Marcel, which runs over Apache
/// Tika's type database, for the most specific of three candidates: the magic-byte type, the type
/// the client declared, and the filename's type. A later candidate only replaces the pick when it
/// is a more specific kind of it, so a `.txt` name can't relabel a JPEG, but a declared
/// `video/mp4` refines a QuickTime container. The stored type decides whether a blob gets
/// variants and whether it's served inline, so it should be what Rails would have stored.
public final class ContentTypeDetector {
    private ContentTypeDetector() {}

    private static final MimeTypes TYPES = MimeTypes.getDefaultMimeTypes();
    private static final MediaTypeRegistry REGISTRY = TYPES.getMediaTypeRegistry();

    /// Where Marcel's own definitions name a type differently from Tika, the name Rails stores.
    private static final Map<String, String> MAGIC_NAMES = Map.of("audio/vnd.wave", "audio/x-wav", "audio/x-flac", "audio/flac",
        "audio/vorbis", "audio/ogg", "video/x-m4v", "video/mp4");
    /// The same by extension, plus `.aac`, which Marcel files under `audio/aac` but only by name.
    private static final Map<String, String> EXTENSION_NAMES = Map.of("audio/vnd.wave", "audio/x-wav", "audio/x-flac", "audio/flac",
        "audio/vorbis", "audio/ogg", "video/x-m4v", "video/mp4", "audio/x-aac", "audio/aac");

    /// How many leading bytes the magic rules can look at.
    public static int magicPrefixLength() {
        return TYPES.getMinLength();
    }

    public static String identify(byte[] head, @Nullable String name, @Nullable String declaredType) {
        var candidates = new LinkedHashSet<String>();
        add(candidates, byMagic(head));
        add(candidates, forDeclaredType(declaredType));
        if (name != null) add(candidates, byExtension(Filename.extname(name)));
        candidates.add(ContentTypes.BINARY);
        String pick = null;
        for (String candidate : candidates) {
            if (pick == null || isKindOf(candidate, pick)) pick = candidate;
        }
        return pick;
    }

    /// The type of a file extension, with or without its dot, or null for an unknown one.
    public static @Nullable String byExtension(@Nullable String extension) {
        if (extension == null || extension.isEmpty() || extension.equals(".")) return null;
        String ext = extension.startsWith(".") ? extension.substring(1) : extension;
        var metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "file." + ext.toLowerCase(Locale.ROOT));
        String type = known(detect(null, metadata));
        return type == null ? null : EXTENSION_NAMES.getOrDefault(type, type);
    }

    /// Tika's text heuristic (no control bytes means `text/plain`) isn't a magic match, and Marcel
    /// has none: plain text is recognised by its name or declared type instead.
    private static @Nullable String byMagic(byte[] head) {
        if (head.length == 0) return null;
        String type = known(detect(head, new Metadata()));
        return type == null || type.equals("text/plain") ? null : MAGIC_NAMES.getOrDefault(type, type);
    }

    /// Declared types are lowercased and stripped of parameters; binary says nothing.
    static @Nullable String forDeclaredType(@Nullable String declared) {
        if (declared == null) return null;
        String lower = declared.toLowerCase(Locale.ROOT);
        int end = 0;
        while (end < lower.length() && ";, \t\n\r\u000b\f".indexOf(lower.charAt(end)) < 0) end++;
        String mediaType = lower.substring(0, end);
        return mediaType.contains("/") && !mediaType.equals(ContentTypes.BINARY) ? mediaType : null;
    }

    private static boolean isKindOf(String child, String parent) {
        MediaType c = REGISTRY.normalize(MediaType.parse(child));
        MediaType p = REGISTRY.normalize(MediaType.parse(parent));
        return c != null && p != null && (c.equals(p) || REGISTRY.isSpecializationOf(c, p));
    }

    private static String detect(byte @Nullable [] head, Metadata metadata) {
        try {
            return TYPES.detect(head == null ? null : new ByteArrayInputStream(head), metadata).getBaseType().toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static @Nullable String known(String type) {
        return ContentTypes.BINARY.equals(type) ? null : type;
    }

    private static void add(LinkedHashSet<String> candidates, @Nullable String type) {
        if (type != null) candidates.add(type);
    }
}
