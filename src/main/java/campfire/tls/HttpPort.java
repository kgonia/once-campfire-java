package campfire.tls;

import io.smallrye.common.annotation.Identifier;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// With `TLS_DOMAIN`, the HTTP port does what autocert's `HTTPHandler` with Thruster's redirect
/// does: answers HTTP-01 challenges and sends everything else to HTTPS. It doesn't serve the app.
@ApplicationScoped
@NullMarked
public class HttpPort {
    private static final String CHALLENGES = "/.well-known/acme-challenge/";

    @Inject
    AcmeConfig config;
    @Inject
    @Identifier(Certificates.TLS_CONFIGURATION)
    Certificates certificates;

    void routes(@Observes Router router) {
        Set<String> domains = Set.copyOf(config.domains());
        if (domains.isEmpty()) return;
        // Ahead of every route of Front's (-310 and later). The connection, not the request: a
        // forwarded scheme doesn't make a plain connection secure.
        router.route().order(-400).handler(rc -> {
            if (rc.request().connection().isSsl()) rc.next();
            else handle(rc, domains);
        });
    }

    private void handle(RoutingContext rc, Set<String> domains) {
        HttpServerRequest request = rc.request();
        String host = Objects.requireNonNullElse(request.getHeader("Host"), "").toLowerCase(Locale.ROOT);
        String path = Objects.requireNonNullElse(request.path(), "/");
        if (path.startsWith(CHALLENGES)) {
            if (!domains.contains(host)) error(rc, 403, "acme/autocert: host \"" + host + "\" not configured in HostWhitelist");
            else answer(rc, certificates.keyAuthorization(path.substring(CHALLENGES.length())));
            return;
        }
        HttpServerResponse response = rc.response().putHeader("Connection", "close");
        String name = AcmeConfig.normalize(List.of(withoutPort(host))).stream().findFirst().orElse("");
        if (!domains.contains(name)) {
            error(rc, 421, "Misdirected Request");
            return;
        }
        // Thruster's redirect names no port: HTTPS_PORT is meant to be 443 to the outside.
        String url = "https://" + name + path + (request.query() == null ? "" : "?" + request.query());
        response.setStatusCode(301).putHeader("Location", url);
        if (request.method() == HttpMethod.GET || request.method() == HttpMethod.HEAD) response.putHeader("Content-Type", "text/html; charset=utf-8");
        response.end(request.method() == HttpMethod.GET ? "<a href=\"" + escape(url) + "\">Moved Permanently</a>.\n\n" : "");
    }

    private static void answer(RoutingContext rc, @Nullable String keyAuthorization) {
        if (keyAuthorization == null) error(rc, 404, "acme/autocert: certificate cache miss");
        else rc.response().putHeader("Content-Type", "text/plain; charset=utf-8").end(keyAuthorization);
    }

    /// `net.SplitHostPort`, or the host as given.
    static String withoutPort(String host) {
        if (host.startsWith("[")) {
            int end = host.indexOf("]:");
            return end < 0 ? host : host.substring(1, end);
        }
        int colon = host.lastIndexOf(':');
        return colon < 0 || host.indexOf(':') != colon ? host : host.substring(0, colon);
    }

    /// `http.Error`.
    private static void error(RoutingContext rc, int status, String message) {
        rc.response().setStatusCode(status).putHeader("Content-Type", "text/plain; charset=utf-8")
            .putHeader("X-Content-Type-Options", "nosniff").end(message + "\n");
    }

    /// `html.EscapeString`.
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&#34;").replace("'", "&#39;");
    }
}
