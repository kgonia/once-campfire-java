package campfire.bots;

import campfire.integrations.net.Network;
import campfire.integrations.net.PinnedHttp;
import campfire.integrations.net.PinnedHttp.HttpException;
import campfire.integrations.net.Url;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Posts a message to a bot's webhook and reads what the bot answers.
///
/// Deliberately not guarded against private addresses, unlike link previews: only an
/// administrator sets the URL, and it may well point at an internal service. Connecting and each
/// read time out after 7 seconds, which is itself answered with a text reply. Unlike Rails, the
/// whole delivery must finish within a minute, and the reply is read up to 100 MB.
@NullMarked
final class WebhookClient {
    private WebhookClient() {}

    static final Duration ENDPOINT_TIMEOUT = Duration.ofSeconds(7);
    static final Duration DELIVERY_DEADLINE = Duration.ofSeconds(60);
    static final long MAX_REPLY_SIZE = 100L * 1024 * 1024;

    /// What the bot says back in the message's room.
    sealed interface Reply {
        record None() implements Reply {}

        record Text(String text) implements Reply {}

        /// `filename` is "attachment.<extension of the registered type>" ("attachment." for an
        /// unregistered one); `contentType` the registered name, which for a synonym differs from
        /// what was sent.
        record File(byte[] data, String filename, String contentType) implements Reply {}
    }

    /// A delivery that failed the way Rails' job does: a bad URL, a connection failure other than
    /// a timeout, an unparseable reply content type, or an oversized reply.
    static final class DeliveryException extends Exception {
        DeliveryException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    private static final PinnedHttp.Timeouts TIMEOUTS = new PinnedHttp.Timeouts(ENDPOINT_TIMEOUT, ENDPOINT_TIMEOUT, DELIVERY_DEADLINE);

    static Reply deliver(Network net, String url, String payload) throws DeliveryException {
        long started = System.nanoTime();
        try {
            return post(net, url, payload);
        } catch (HttpException e) {
            if (e.kind != HttpException.Kind.OPEN_TIMEOUT && e.kind != HttpException.Kind.READ_TIMEOUT) throw new DeliveryException(String.valueOf(e.getMessage()), e);
            boolean overall = System.nanoTime() - started >= DELIVERY_DEADLINE.toNanos();
            return new Reply.Text("Failed to respond within " + (overall ? DELIVERY_DEADLINE : ENDPOINT_TIMEOUT).toSeconds() + " seconds");
        }
    }

    private static Reply post(Network net, String url, String payload) throws DeliveryException, HttpException {
        Url uri;
        try {
            uri = Url.parse(url);
        } catch (URISyntaxException e) {
            throw new DeliveryException("bad URI: " + url, e);
        }
        if (!uri.isHttp()) throw new DeliveryException("not an HTTP URI", null);
        String host = uri.host();
        if (host == null || host.isEmpty()) throw new DeliveryException("no host component for URI", null);
        int port = uri.port();
        if (port < 0 || port > 65535) throw new DeliveryException("invalid port", null);
        InetAddress address;
        try {
            List<InetAddress> addresses = net.resolver().lookup(uri.hostname());
            if (addresses.isEmpty()) throw new DeliveryException("getaddrinfo: Name or service not known", null);
            address = addresses.getFirst();
        } catch (IOException e) {
            throw new DeliveryException("Failed to open TCP connection to " + host + ":" + port + " (getaddrinfo: " + e.getMessage() + ")", e);
        }
        var target = new PinnedHttp.Target(uri.isHttps(), host, port, address);
        var request = new PinnedHttp.Request("POST", uri.requestUri(), List.of(new PinnedHttp.Header("Content-Type", "application/json")),
            payload.getBytes(StandardCharsets.UTF_8));
        try (var response = PinnedHttp.exchange(net, target, request, TIMEOUTS)) {
            String contentType = response.contentType();
            byte[] body = response.readBody(MAX_REPLY_SIZE);
            if (body == null) throw new DeliveryException("the reply is larger than " + MAX_REPLY_SIZE / 1024 / 1024 + " MB", null);
            return reply(response.status, contentType, body);
        }
    }

    private static Reply reply(int status, @Nullable String contentType, byte[] body) throws DeliveryException {
        if (contentType == null) return new Reply.None();
        if (status == 200 && (contentType.equals("text/html") || contentType.equals("text/plain"))) {
            return new Reply.Text(new String(body, StandardCharsets.UTF_8));
        }
        String[] registered = lookup(contentType);
        return new Reply.File(body, "attachment." + registered[0], registered[1]);
    }

    /// `Mime::Type.lookup`: the extension and name of a registered type (by its name or a
    /// synonym, parameters ignored), or "" and the type itself for a valid unregistered one.
    private static String[] lookup(String contentType) throws DeliveryException {
        String[] found = MIME_TYPES.get(contentType);
        if (found != null) return found;
        int semicolon = contentType.indexOf(';');
        String bare = (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).replaceFirst("[ \\t\\n\\x0B\\f\\r\\x00]+$", "");
        found = MIME_TYPES.get(bare);
        if (found != null) return found;
        if (MIME_REGEXP.matcher(bare).matches()) return new String[] {"", bare};
        throw new DeliveryException("\"" + bare + "\" is not a valid MIME type", null);
    }

    private static final String NAME = "[a-zA-Z0-9][a-zA-Z0-9!#$&\\-^_.+]{0,126}";
    private static final String VALUE = "(?:" + NAME + "|\"[^\"\\r\\\\]*\")";
    private static final String PARAMETER = " *; *" + NAME + "(?:=" + VALUE + ")?";
    private static final Pattern MIME_REGEXP = Pattern.compile("(?:\\*/\\*|" + NAME + "/(?:\\*|" + NAME + ")(?:" + PARAMETER + ")*[ \\t\\n\\x0B\\f\\r]*)");

    /// The types the app registers (Action Dispatch's and turbo-rails'): extension, then name.
    private static final Map<String, String[]> MIME_TYPES = Map.ofEntries(
        type("text/html", "html", "text/html"), type("application/xhtml+xml", "html", "text/html"),
        type("text/plain", "text", "text/plain"), type("text/javascript", "js", "text/javascript"),
        type("application/javascript", "js", "text/javascript"), type("application/x-javascript", "js", "text/javascript"),
        type("text/css", "css", "text/css"), type("text/calendar", "ics", "text/calendar"), type("text/csv", "csv", "text/csv"),
        type("text/vcard", "vcf", "text/vcard"), type("text/vtt", "vtt", "text/vtt"), type("vtt", "vtt", "text/vtt"),
        type("text/markdown", "md", "text/markdown"), type("image/png", "png", "image/png"), type("image/jpeg", "jpeg", "image/jpeg"),
        type("image/gif", "gif", "image/gif"), type("image/bmp", "bmp", "image/bmp"), type("image/tiff", "tiff", "image/tiff"),
        type("image/svg+xml", "svg", "image/svg+xml"), type("image/webp", "webp", "image/webp"), type("video/mpeg", "mpeg", "video/mpeg"),
        type("audio/mpeg", "mp3", "audio/mpeg"), type("audio/ogg", "ogg", "audio/ogg"), type("audio/aac", "m4a", "audio/aac"),
        type("audio/mp4", "m4a", "audio/aac"), type("video/webm", "webm", "video/webm"), type("video/mp4", "mp4", "video/mp4"),
        type("font/otf", "otf", "font/otf"), type("font/ttf", "ttf", "font/ttf"), type("font/woff", "woff", "font/woff"),
        type("font/woff2", "woff2", "font/woff2"), type("application/xml", "xml", "application/xml"), type("text/xml", "xml", "application/xml"),
        type("application/x-xml", "xml", "application/xml"), type("application/rss+xml", "rss", "application/rss+xml"),
        type("application/atom+xml", "atom", "application/atom+xml"), type("application/x-yaml", "yaml", "application/x-yaml"),
        type("text/yaml", "yaml", "application/x-yaml"), type("multipart/form-data", "multipart_form", "multipart/form-data"),
        type("application/x-www-form-urlencoded", "url_encoded_form", "application/x-www-form-urlencoded"),
        type("application/json", "json", "application/json"), type("text/x-json", "json", "application/json"),
        type("application/jsonrequest", "json", "application/json"), type("application/problem+json", "json", "application/json"),
        type("application/pdf", "pdf", "application/pdf"), type("application/zip", "zip", "application/zip"),
        type("application/gzip", "gzip", "application/gzip"), type("application/x-gzip", "gzip", "application/gzip"),
        type("text/vnd.turbo-stream.html", "turbo_stream", "text/vnd.turbo-stream.html"));

    private static Map.Entry<String, String[]> type(String name, String extension, String registered) {
        return Map.entry(name, new String[] {extension, registered});
    }
}
