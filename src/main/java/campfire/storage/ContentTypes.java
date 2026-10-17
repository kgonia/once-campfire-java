package campfire.storage;

import java.util.List;

/// The effective `config.active_storage` content type lists: Rails 8.2's engine defaults with
/// `load_defaults 8.2`, minus the types `config/initializers/vips.rb` removes.
public final class ContentTypes {
    private ContentTypes() {}

    /// `variable_content_types` without bmp, ico and psd (config/initializers/vips.rb).
    public static final List<String> VARIABLE =
        List.of("image/png", "image/gif", "image/jpeg", "image/tiff", "image/webp", "image/avif", "image/heic", "image/heif");

    /// `web_image_content_types` (webp since `load_defaults 7.2`).
    public static final List<String> WEB_IMAGE = List.of("image/png", "image/jpeg", "image/gif", "image/webp");

    public static final List<String> ALLOWED_INLINE = List.of("image/webp", "image/avif", "image/png", "image/gif", "image/jpeg", "image/tiff",
        "image/bmp", "image/vnd.adobe.photoshop", "image/vnd.microsoft.icon", "application/pdf");

    public static final List<String> SERVE_AS_BINARY = List.of("text/html", "image/svg+xml", "application/postscript",
        "application/x-shockwave-flash", "text/xml", "application/xml", "application/xhtml+xml", "application/mathml+xml", "text/cache-manifest");

    public static final String BINARY = "application/octet-stream";

    public static boolean isVariable(String contentType) {
        return contentType != null && VARIABLE.contains(contentType);
    }

    public static boolean isWebImage(String contentType) {
        return contentType != null && WEB_IMAGE.contains(contentType);
    }

    /// `content_type_for_serving`.
    public static String forServing(String contentType) {
        return contentType != null && SERVE_AS_BINARY.contains(contentType) ? BINARY : contentType;
    }

    /// `forced_disposition_for_serving`: "attachment" for binary or non-inline types, else null.
    public static String forcedDisposition(String contentType) {
        if (contentType == null) return "attachment";
        return SERVE_AS_BINARY.contains(contentType) || !ALLOWED_INLINE.contains(contentType) ? "attachment" : null;
    }
}
