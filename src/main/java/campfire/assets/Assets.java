package campfire.assets;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/// The digested frontend, built by {@link AssetCompiler} into the classpath: logical paths to
/// digested ones, the importmap tags, and the files themselves (kept in memory, gzipped beside).
public final class Assets {
    public static final String PREFIX = "/assets";

    public record StaticFile(byte[] body, byte[] gzip, String contentType, boolean immutable) {}

    private final Map<String, String> digested = new HashMap<>();
    private final Map<String, StaticFile> files = new HashMap<>();
    private final String importmapTags;
    private final String stylesheetTags;
    private final String stylesheetLinkHeader;
    private static final int MAX_LINK_HEADER = 1_000;

    public Assets() {
        for (String line : read("/campfire/assets/manifest.tsv").split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) digested.put(line.substring(0, tab), line.substring(tab + 1));
        }
        importmapTags = read("/campfire/assets/importmap.html");
        var tags = new StringBuilder();
        var links = new java.util.ArrayList<String>();
        for (String logical : read("/campfire/assets/stylesheets.txt").split("\n")) {
            if (logical.isBlank()) continue;
            String path = path(logical);
            if (!tags.isEmpty()) tags.append('\n');
            tags.append("<link rel=\"stylesheet\" href=\"").append(path).append("\" data-turbo-track=\"reload\" />");
            links.add("<" + path + ">; rel=preload; as=style; nopush");
        }
        stylesheetTags = tags.toString();
        var link = new StringBuilder();
        for (String preload : links) {
            // A link that would push the header past Rails' 1,000 bytes is left out; a later,
            // shorter one may still fit.
            if (link.length() + preload.length() > MAX_LINK_HEADER) continue;
            if (!link.isEmpty()) link.append(',');
            link.append(preload);
        }
        stylesheetLinkHeader = link.toString();
        for (String url : read("/campfire/assets/files.txt").split("\n")) {
            if (url.isBlank() || url.endsWith(".gz")) continue;
            byte[] body = bytes("/public" + url);
            byte[] gzip = url.startsWith(PREFIX + "/") ? optionalBytes("/public" + url + ".gz") : null;
            files.put(url, new StaticFile(body, gzip, contentType(url), url.startsWith(PREFIX + "/")));
        }
    }

    /// `asset_path(logical)`: the digested URL, or `/<logical>` when unknown.
    public String path(String logical) {
        String d = digested.get(logical);
        return d == null ? "/" + logical : PREFIX + "/" + d;
    }

    /// `javascript_importmap_tags`
    public String importmapTags() {
        return importmapTags;
    }

    /// `stylesheet_link_tag :all, "data-turbo-track": "reload"`
    public String stylesheetTags() {
        return stylesheetTags;
    }

    /// The `Link` header preloading the stylesheets, for pages in the application layout.
    public String stylesheetLinkHeader() {
        return stylesheetLinkHeader;
    }

    public StaticFile file(String path) {
        return files.get(path);
    }

    private static String read(String resource) {
        return new String(bytes(resource), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String resource) {
        byte[] b = optionalBytes(resource);
        if (b == null) throw new IllegalStateException("missing resource " + resource + " (run the asset compiler)");
        return b;
    }

    private static byte[] optionalBytes(String resource) {
        try (InputStream in = Assets.class.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String contentType(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot + 1);
        return switch (ext) {
            case "css" -> "text/css";
            case "js" -> "text/javascript";
            case "json", "map" -> "application/json";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "ico" -> "image/vnd.microsoft.icon";
            case "mp3" -> "audio/mpeg";
            case "html" -> "text/html";
            case "txt" -> "text/plain";
            case "woff2" -> "font/woff2";
            case "woff" -> "font/woff";
            case "webmanifest" -> "application/manifest+json";
            default -> "application/octet-stream";
        };
    }
}
