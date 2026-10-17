package campfire.storage;

/// `ActiveStorage::Filename`, with Ruby's `File.extname`/`File.basename` semantics (Unix).
///
/// `raw` is what `active_storage_blobs.filename` stores; {@link #sanitized} is what URLs and
/// `Content-Disposition` headers show (`Filename#to_s`).
public record Filename(String raw) {
    private static final String UNSAFE = "\u202E%$|:;/<>?*\"\t\r\n\\";

    /// `File.basename(filename, extension_with_delimiter)`.
    public String base() {
        String base = basename(raw);
        String ext = extensionWithDelimiter();
        return !ext.isEmpty() && base.length() > ext.length() && base.endsWith(ext) ? base.substring(0, base.length() - ext.length()) : base;
    }

    public String extensionWithDelimiter() {
        return extname(raw);
    }

    /// `extension_without_delimiter`, aliased as `extension`.
    public String extension() {
        String ext = extensionWithDelimiter();
        return ext.isEmpty() ? "" : ext.substring(1);
    }

    /// `strip`, then the RTL override, path separators and shell/HTML metacharacters become "-".
    /// (Invalid UTF-8 was already replaced with U+FFFD when the name became a Java string.)
    public String sanitized() {
        String stripped = rubyStrip(raw);
        var out = new StringBuilder(stripped.length());
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            out.append(UNSAFE.indexOf(c) >= 0 ? '-' : c);
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return sanitized();
    }

    /// Ruby's `String#strip`: NUL and ASCII whitespace at both ends.
    static String rubyStrip(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isRubySpace(s.charAt(start))) start++;
        while (end > start && isRubySpace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private static boolean isRubySpace(char c) {
        return c == 0 || c == ' ' || c == '\t' || c == '\n' || c == 0x0b || c == '\f' || c == '\r';
    }

    /// `File.basename(path)`: the last component, ignoring trailing slashes.
    static String basename(String path) {
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') end--;
        if (end == 0) return path.isEmpty() ? "" : "/";
        String trimmed = path.substring(0, end);
        int slash = trimmed.lastIndexOf('/');
        return slash < 0 ? trimmed : trimmed.substring(slash + 1);
    }

    /// `File.extname(path)`: leading dots don't start an extension, and a trailing dot is an
    /// extension of its own (`"foo."` is `"."`).
    static String extname(String path) {
        String base = basename(path);
        int start = 0;
        while (start < base.length() && base.charAt(start) == '.') start++;
        int dot = base.lastIndexOf('.');
        return dot >= start && start < base.length() ? base.substring(dot) : "";
    }
}
