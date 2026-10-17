package campfire.web;

import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Content negotiation as Rails does it. A request's formats come from, in order: the `.json`
/// style path suffix or `format` parameter, a non-browser `Accept` header (a browser's ends in
/// `*/*` and is ignored), and HTML otherwise (JS for XHR). Formats are the short names Rails
/// registers (`html`, `json`, `svg`, ...); `*/*` is `all`.
@NullMarked
public final class Formats {
    private Formats() {}

    private static final Map<String, String> MIME = Map.ofEntries(Map.entry("text/html", "html"), Map.entry("application/xhtml+xml", "html"),
        Map.entry("text/plain", "text"), Map.entry("text/javascript", "js"), Map.entry("application/javascript", "js"),
        Map.entry("application/x-javascript", "js"), Map.entry("text/css", "css"), Map.entry("text/calendar", "ics"), Map.entry("text/csv", "csv"),
        Map.entry("text/vcard", "vcf"), Map.entry("text/vtt", "vtt"), Map.entry("image/png", "png"), Map.entry("image/jpeg", "jpeg"),
        Map.entry("image/pjpeg", "jpeg"), Map.entry("image/gif", "gif"), Map.entry("image/bmp", "bmp"), Map.entry("image/tiff", "tiff"),
        Map.entry("image/svg+xml", "svg"), Map.entry("image/webp", "webp"), Map.entry("video/mpeg", "mpeg"), Map.entry("audio/mpeg", "mp3"),
        Map.entry("audio/ogg", "ogg"), Map.entry("audio/aac", "m4a"), Map.entry("video/webm", "webm"), Map.entry("video/mp4", "mp4"),
        Map.entry("font/otf", "otf"), Map.entry("font/ttf", "ttf"), Map.entry("font/woff", "woff"), Map.entry("font/woff2", "woff2"),
        Map.entry("application/xml", "xml"), Map.entry("text/xml", "xml"), Map.entry("application/x-xml", "xml"),
        Map.entry("application/rss+xml", "rss"), Map.entry("application/atom+xml", "atom"), Map.entry("application/x-yaml", "yaml"),
        Map.entry("multipart/form-data", "multipart_form"), Map.entry("application/x-www-form-urlencoded", "url_encoded_form"),
        Map.entry("application/json", "json"), Map.entry("text/x-json", "json"), Map.entry("application/jsonrequest", "json"),
        Map.entry("application/pdf", "pdf"), Map.entry("application/zip", "zip"), Map.entry("application/gzip", "gzip"),
        Map.entry("text/vnd.turbo-stream.html", "turbo_stream"), Map.entry("*/*", "all"));

    private static final Set<String> EXTENSIONS = Set.of("html", "text", "js", "css", "ics", "csv", "vcf", "vtt", "png", "jpeg", "gif", "bmp",
        "tiff", "svg", "webp", "mpeg", "mp3", "ogg", "m4a", "webm", "mp4", "otf", "ttf", "woff", "woff2", "xml", "rss", "atom", "yaml", "json",
        "pdf", "zip", "gzip", "turbo_stream");

    /// The request's acceptable formats, most preferred first; unregistered types are dropped, so
    /// the list can be empty (`Accept: image/*`).
    public static List<String> of(CurrentRequest request) {
        String explicit = explicitFormat(request);
        if (explicit != null) {
            String format = byExtension(explicit);
            return format == null ? List.of() : List.of(format);
        }
        if (validAcceptHeader(request)) {
            String accept = request.header("accept");
            if (accept == null || accept.isBlank()) {
                String type = request.header("content-type");
                if (type == null) return List.of();
                String format = MIME.get(type.split("[,;]")[0].strip().toLowerCase(Locale.ROOT));
                return format == null ? List.of() : List.of(format);
            }
            return parseAccept(accept);
        }
        return List.of(xhr(request) ? "js" : "html");
    }

    /// The first of `offered` the client accepts (the first offered for `*/*`), or a 406.
    public static String respondTo(CurrentRequest request, String... offered) {
        for (String format : of(request)) {
            if (format.equals("all")) return offered[0];
            for (String candidate : offered) if (candidate.equals(format)) return candidate;
        }
        throw new Halt(notAcceptable(request));
    }

    /// `head status` as Rails' own controllers answer it: no body, typed as the format the
    /// request asked for first (HTML for `*/*` or nothing recognized), untyped when bodiless by
    /// definition. `/disk/<key>/moon.png` that isn't found is an empty `image/png`.
    public static Response head(CurrentRequest request, int status) {
        if (status < 200 || status == 204 || status == 205 || status == 304) return Response.status(status).build();
        return Response.status(status).entity(new byte[0]).header("Content-Type", renderedType(request)).build();
    }

    /// The MIME type of the first accepted format, `text/html` for `*/*` or none.
    static String renderedType(CurrentRequest request) {
        List<String> formats = of(request);
        String type = canonicalType(formats.isEmpty() || formats.getFirst().equals("all") ? "html" : formats.getFirst());
        return type != null ? type : "text/html";
    }

    /// The type a `.json`-style path suffix or `format` parameter asks for, or null for one Rails
    /// doesn't register.
    static @Nullable String typeOfExtension(String extension) {
        String format = byExtension(extension);
        return format == null ? null : canonicalType(format);
    }

    private static @Nullable String canonicalType(String format) {
        for (String type : CANONICAL_TYPES) if (format.equals(MIME.get(type))) return type;
        return null;
    }

    /// Each format's own type, the one `Mime[format]` names (`jpeg` is `image/jpeg`, not
    /// `image/pjpeg`).
    private static final List<String> CANONICAL_TYPES = List.of("text/html", "text/plain", "text/javascript", "text/css", "text/calendar",
        "text/csv", "text/vcard", "text/vtt", "image/png", "image/jpeg", "image/gif", "image/bmp", "image/tiff", "image/svg+xml", "image/webp",
        "video/mpeg", "audio/mpeg", "audio/ogg", "audio/aac", "video/webm", "video/mp4", "font/otf", "font/ttf", "font/woff", "font/woff2",
        "application/xml", "application/rss+xml", "application/atom+xml", "application/x-yaml", "multipart/form-data",
        "application/x-www-form-urlencoded", "application/json", "application/pdf", "application/zip", "application/gzip",
        "text/vnd.turbo-stream.html");

    /// Whether a render varies on `Accept`: its format came from that header. Only endpoints that
    /// offer more than one type say so.
    public static boolean variesOnAccept(CurrentRequest request) {
        return explicitFormat(request) == null && validAcceptHeader(request);
    }

    /// The 406 Rails' exception pages answer: the status and reason in the request's format for
    /// JSON, XML and YAML, an empty page otherwise.
    static Response notAcceptable(CurrentRequest request) {
        request.discardChanges();
        List<String> formats = of(request);
        String format = formats.isEmpty() ? "html" : formats.getFirst();
        String body = switch (format) {
            case "json" -> "{\"status\":406,\"error\":\"Not Acceptable\"}";
            case "xml" -> "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<hash>\n  <status type=\"integer\">406</status>\n  <error>Not Acceptable</error>\n</hash>\n";
            case "yaml" -> "---\n:status: 406\n:error: Not Acceptable\n";
            default -> "";
        };
        String type = body.isEmpty() ? "text/html" : mimeType(format);
        byte[] bytes = request.isHead() ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        return Response.status(406).entity(bytes).header("Content-Type", type + "; charset=UTF-8").header("Content-Length", bytes.length).build();
    }

    private static String mimeType(String format) {
        return switch (format) {
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            default -> "application/x-yaml";
        };
    }

    private static @Nullable String explicitFormat(CurrentRequest request) {
        String suffix = request.format();
        return suffix != null ? suffix : request.params().string("format");
    }

    private static @Nullable String byExtension(String extension) {
        String e = extension.toLowerCase(Locale.ROOT);
        if (e.equals("txt")) return "text";
        if (e.equals("jpg")) return "jpeg";
        return EXTENSIONS.contains(e) ? e : null;
    }

    private static boolean xhr(CurrentRequest request) {
        String requestedWith = request.header("x-requested-with");
        return requestedWith != null && requestedWith.toLowerCase(Locale.ROOT).contains("xmlhttprequest");
    }

    private static boolean validAcceptHeader(CurrentRequest request) {
        String accept = request.header("accept");
        boolean present = accept != null && !accept.isBlank();
        String contentType = request.header("content-type");
        if (xhr(request) && (present || (contentType != null && !contentType.isEmpty()))) return true;
        if (accept == null || !present) return false;
        String compact = accept.replaceAll("\\s", "");
        return !(compact.contains(",*/*") || compact.contains("*/*,"));
    }

    /// By quality, ties in header order; `text/*` and `application/*` stand for every registered
    /// type under them.
    static List<String> parseAccept(String accept) {
        record Item(int index, String type, double quality) {}
        var items = new ArrayList<Item>();
        int index = 0;
        for (String part : accept.split(",")) {
            String[] pieces = part.split(";");
            String type = pieces[0].strip().toLowerCase(Locale.ROOT);
            if (type.isEmpty()) continue;
            double quality = 1.0;
            for (int i = 1; i < pieces.length; i++) {
                String parameter = pieces[i].strip();
                if (!parameter.startsWith("q=")) continue;
                try {
                    quality = Double.parseDouble(parameter.substring(2));
                } catch (NumberFormatException e) {
                    quality = 0;
                }
            }
            if (type.equals("text/*") || type.equals("application/*")) {
                String prefix = type.substring(0, type.length() - 1);
                for (String registered : MIME.keySet()) if (registered.startsWith(prefix)) items.add(new Item(index++, registered, quality));
            } else {
                items.add(new Item(index++, type, quality));
            }
        }
        items.sort((a, b) -> a.quality() != b.quality() ? Double.compare(b.quality(), a.quality()) : Integer.compare(a.index(), b.index()));
        var formats = new ArrayList<String>();
        for (Item item : items) {
            String format = MIME.get(item.type());
            if (format != null && !formats.contains(format)) formats.add(format);
        }
        return formats;
    }
}
