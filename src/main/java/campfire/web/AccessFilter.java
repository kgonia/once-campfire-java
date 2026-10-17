package campfire.web;

import campfire.db.Database;
import campfire.sessions.Authentication;
import campfire.users.Ban;
import campfire.useragent.AllowBrowser;
import campfire.view.Pages;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Response;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.jspecify.annotations.Nullable;

/// Runs before every resource method, in this order: banned IPs may not write, sign-in (see
/// {@link Access}), bots kept out where not allowed, the cross-site write check, then old browsers
/// get a page asking them to upgrade.
public class AccessFilter {
    @Inject
    CurrentRequest request;
    @Inject
    Authentication authentication;
    @Inject
    Database db;
    @Inject
    Pages pages;

    /// The `.json` / `.js` suffix of the last path segment becomes the request's format, as Rails
    /// routes treat it. Active Storage paths end in real filenames, which keep their extension
    /// while it still sets the format.
    @ServerRequestFilter(preMatching = true)
    public void format(ContainerRequestContext context) {
        URI uri = context.getUriInfo().getRequestUri();
        String path = uri.getRawPath();
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash + 1 || dot == path.length() - 1) return;
        String format = path.substring(dot + 1);
        if (format.contains("?")) return;
        request.format(format);
        if (path.startsWith("/rails/active_storage/")) return;
        context.setRequestUri(uri.resolve(path.substring(0, dot) + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())));
    }

    @ServerRequestFilter
    public @Nullable Response check(ContainerRequestContext context, ResourceInfo resource) {
        var pathParams = new HashMap<String, String>();
        context.getUriInfo().getPathParameters().forEach((name, values) -> pathParams.put(name, values.getFirst()));
        request.addPathParams(pathParams);
        if (request.malformedBody()) return Response.status(400).entity(new byte[0]).header("Content-Type", "text/html; charset=UTF-8").build();
        if (has(resource, Access.Framework.class)) {
            request.markFramework();
            return null;
        }
        if (request.isGet()) request.dataVersionAtStart(db.dataVersion());

        boolean isPublic = has(resource, Access.Public.class);
        boolean guestOnly = has(resource, Access.GuestOnly.class);
        if (!request.isGet() && db.read(conn -> Ban.banned(conn, request.remoteIp()))) return request.head(429);
        if (!isPublic && !guestOnly && !authentication.resume(request) && !authentication.authenticateBot(request)) {
            request.setSessionValue("return_to_after_authenticating", request.requestUrl());
            return request.redirect(Paths.NEW_SESSION);
        }
        if (request.authenticatedByBot() && !has(resource, Access.BotsAllowed.class)) return request.head(403);
        if (!request.authenticatedByBot() && !has(resource, Access.NoForgeryProtection.class) && !sameOriginWrite()) return request.head(422);
        if (AllowBrowser.blocked(request.userAgent())) return pages.incompatibleBrowser(ownLayout(request.path()));
        if (guestOnly && authentication.resume(request)) return request.redirect(Paths.ROOT);
        return null;
    }

    private static boolean has(ResourceInfo resource, Class<? extends Annotation> annotation) {
        return resource.getResourceMethod().isAnnotationPresent(annotation) || resource.getResourceClass().isAnnotationPresent(annotation);
    }

    /// Cross-site request forgery protection without tokens: writes must come from this origin by
    /// the browser's own account (`Sec-Fetch-Site: same-origin` or `same-site`, and a matching
    /// `Origin` if one is sent). Clients that send neither header are only trusted over plain HTTP.
    boolean sameOriginWrite() {
        if (request.isGet()) return true;
        String origin = request.header("origin");
        if (origin != null && (origin.equals("null") || !sameOrigin(origin))) return false;
        String site = request.header("sec-fetch-site");
        if (site == null) return !request.ssl();
        return switch (site.toLowerCase(Locale.ROOT)) {
            case "same-origin", "same-site" -> true;
            default -> false;
        };
    }

    private boolean sameOrigin(String origin) {
        try {
            URI uri = URI.create(origin);
            String actual = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
            return actual.equalsIgnoreCase(request.baseUrl());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /// The message pages always render in the full layout, Turbo-Frame request or not, so the
    /// upgrade page does too there.
    private static final Pattern MESSAGE_PAGES = Pattern.compile(
        "^(/rooms/[^/]+(/[^/]+)?/messages(/[^/]+(/edit)?)?|/messages(/[^/]+(/edit)?)?)(\\.[^/]*)?$");

    private static boolean ownLayout(String path) {
        return MESSAGE_PAGES.matcher(path).matches() && !path.contains("/boosts");
    }
}
