package campfire.web;

import campfire.Config;
import campfire.users.Ban;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.net.InetAddress;
import org.jspecify.annotations.Nullable;

/// Where the request was sent and who sent it, as Rails reads them behind a proxy. The scheme
/// and host come from Vert.x, which `quarkus.http.proxy.*` points at a trusted proxy's
/// `X-Forwarded-Proto` and `X-Forwarded-Host`.
@RequestScoped
public class RequestOrigin {
    @Inject
    Config config;
    @Inject
    RoutingContext routing;

    /// Links on a page ask for these over and over.
    private @Nullable Boolean ssl;
    private @Nullable String baseUrl;
    private @Nullable String remoteIp;

    /// Every request counts as HTTPS unless `DISABLE_SSL` is set (Rails' `assume_ssl`); then a
    /// trusted proxy's `X-Forwarded-Proto` decides.
    public boolean ssl() {
        if (ssl == null) ssl = !config.disableSsl() || routing.request().isSSL();
        return ssl;
    }

    public String protocol() {
        return ssl() ? "https://" : "http://";
    }

    /// The host without the scheme's default port.
    public String hostWithPort() {
        String host = Front.authority(routing.request());
        if (host == null || host.isBlank()) return "localhost";
        if (ssl() && host.endsWith(":443")) return host.substring(0, host.length() - 4);
        if (!ssl() && host.endsWith(":80")) return host.substring(0, host.length() - 3);
        return host;
    }

    public String host() {
        String h = hostWithPort();
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            return close > 0 ? h.substring(0, close + 1) : h;
        }
        int colon = h.lastIndexOf(':');
        return colon > 0 ? h.substring(0, colon) : h;
    }

    public String baseUrl() {
        if (baseUrl == null) baseUrl = protocol() + hostWithPort();
        return baseUrl;
    }

    public String url(String path) {
        return baseUrl() + path;
    }

    public String requestUrl() {
        HttpServerRequest request = routing.request();
        String query = request.query();
        return baseUrl() + request.path() + (query == null || query.isEmpty() ? "" : "?" + query);
    }

    /// The client's address: the last untrusted hop of a trusted proxy's `X-Forwarded-For`, else
    /// the peer. Bans key on it. Quarkus' own forwarded address is the first hop, which the client
    /// chooses.
    public String remoteIp() {
        if (remoteIp == null) remoteIp = remoteIp(routing.request());
        return remoteIp;
    }

    static String remoteIp(HttpServerRequest request) {
        SocketAddress address = request.connection().remoteAddress();
        String peer = address == null ? null : address.hostAddress();
        String forwarded = String.join(",", request.headers().getAll("x-forwarded-for"));
        if (!forwarded.isEmpty() && isTrustedProxy(peer)) {
            String[] chain = forwarded.split(",");
            for (int i = chain.length - 1; i >= 0; i--) {
                String candidate = chain[i].strip();
                if (!candidate.isEmpty() && !isTrustedProxy(candidate)) return candidate;
            }
            if (chain.length > 0 && !chain[0].isBlank()) return chain[0].strip();
        }
        return peer == null ? "" : peer;
    }

    /// Loopback, private, unique-local and link-local addresses: where a reverse proxy may sit.
    /// `quarkus.http.proxy.trusted-proxies` lists the same ranges.
    static boolean isTrustedProxy(@Nullable String address) {
        InetAddress ip = Ban.literal(address);
        return ip != null && (ip.isLoopbackAddress() || ip.isSiteLocalAddress() || ip.isLinkLocalAddress() || Ban.isUniqueLocal(ip));
    }
}
