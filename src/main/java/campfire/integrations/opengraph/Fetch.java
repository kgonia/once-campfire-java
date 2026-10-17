package campfire.integrations.opengraph;

import campfire.integrations.net.Guard;
import campfire.integrations.net.Network;
import campfire.integrations.net.PinnedHttp;
import campfire.integrations.net.PinnedHttp.HttpException;
import campfire.integrations.net.RubyUri;
import java.net.InetAddress;
import java.time.Duration;

/// `Opengraph::Fetch` (reference/app/models/opengraph/fetch.rb): GET or HEAD against a pinned
/// address, following up to 10 responses (any 3xx is a redirect), each redirect target parsed,
/// required to be http(s), and resolved through the private network guard again. A document
/// must be a 200 `text/html` of at most 5MB, by `Content-Length` and by what's actually read.
final class Fetch {
    private Fetch() {}

    static final String ALLOWED_DOCUMENT_CONTENT_TYPE = "text/html";
    static final int MAX_BODY_SIZE = 5 * 1024 * 1024;
    static final int MAX_REDIRECTS = 10;

    /// Each connect and each read; Rails leaves `Net::HTTP`'s 60 seconds. The unfurl as a whole
    /// has {@link Opengraph#UNFURL_DEADLINE}, which also bounds each exchange.
    private static final PinnedHttp.Timeouts TIMEOUTS = new PinnedHttp.Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(5), Opengraph.UNFURL_DEADLINE);

    /// What the fetch raised (`TooManyRedirectsError`, `RedirectDeniedError`, `URI::InvalidURIError`,
    /// a guard error, or a `Net::HTTP` failure); callers log it and carry on without.
    static final class FetchException extends Exception {
        FetchException(String message, Throwable cause) {
            super(message, cause, false, false);
        }
    }

    /// `fetch_document(url, ip:)`: the body, or null when the response isn't acceptable.
    static byte[] fetchDocument(Network net, RubyUri url, InetAddress ip) throws FetchException {
        try (var response = request(net, url, ip, "GET")) {
            if (response.status != 200 || !ALLOWED_DOCUMENT_CONTENT_TYPE.equals(response.contentType())) return null;
            Long length = response.contentLength();
            if (length != null && length > MAX_BODY_SIZE) return null;
            return response.readBody(MAX_BODY_SIZE);
        } catch (HttpException e) {
            throw new FetchException(e.getMessage(), e);
        }
    }

    /// `fetch_content_type(url, ip:)`: the final response's `Content-Type`, whatever its status.
    static String fetchContentType(Network net, RubyUri url, InetAddress ip) throws FetchException {
        try (var response = request(net, url, ip, "HEAD")) {
            return response.header("content-type");
        }
    }

    private static PinnedHttp.Response request(Network net, RubyUri url, InetAddress ip, String method) throws FetchException {
        for (int i = 0; i < MAX_REDIRECTS; i++) {
            var response = send(net, url, ip, method);
            if (response.status >= 300 && response.status < 400) {
                String location = response.header("location");
                response.close();
                url = RubyUri.parseOrNull(location);
                if (url == null) throw new FetchException("bad URI(is not URI?): " + location, null);
                if (!url.isHttp()) throw new FetchException("Opengraph::Fetch::RedirectDeniedError", null);
                try {
                    ip = Guard.resolve(net.resolver(), url.host() == null ? "" : url.host());
                } catch (Guard.GuardException e) {
                    throw new FetchException(e.getMessage(), e);
                }
            } else {
                return response;
            }
        }
        throw new FetchException("Opengraph::Fetch::TooManyRedirectsError", null);
    }

    /// `Net::HTTP.start(url.host, url.port, ipaddr: ip, use_ssl: url.scheme == "https")` and
    /// `http.request(request_class.new(url))`.
    private static PinnedHttp.Response send(Network net, RubyUri url, InetAddress ip, String method) throws FetchException {
        String host = url.host();
        if (host == null || host.isEmpty() || url.port() == null || url.port() > 65535) throw new FetchException("bad URI: " + url, null);
        boolean https = url.isHttps();
        int port = (int) (long) url.port();
        var target = new PinnedHttp.Target(https, host, port, ip);
        try {
            return PinnedHttp.exchange(net, target, new PinnedHttp.Request(method, url.requestUri()), TIMEOUTS);
        } catch (HttpException e) {
            throw new FetchException(e.getMessage(), e);
        }
    }
}
