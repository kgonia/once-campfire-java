package campfire.web;

import campfire.db.Database;
import campfire.sessions.Authentication;
import campfire.sessions.Viewer;
import campfire.useragent.AllowBrowser;
import campfire.users.Ban;
import campfire.view.Pages;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.jspecify.annotations.Nullable;

/// Runs before every resource method, in this order: banned IPs may not write, sign-in (see
/// {@link Access}), bots kept out where not allowed, the cross-site write check, then old browsers
/// get a page asking them to upgrade.
public class AccessFilter {
    @Inject
    Authentication authentication;
    @Inject
    Viewer viewer;
    @Inject
    RequestOrigin origin;
    @Inject
    RailsSession railsSession;
    @Inject
    Database db;
    @Inject
    Pages pages;

    /// The `.json` / `.js` suffix of the last path segment becomes the request's format, as Rails
    /// routes treat it, and so does a `format` query parameter without one. Either replaces
    /// `Accept`, so `@Produces` negotiates on it too. Active Storage paths end in real filenames,
    /// which keep their extension while it still sets the format.
    @ServerRequestFilter(preMatching = true)
    public void format(ContainerRequestContext context, RoutingContext routing) {
        // The raw path and query straight from Vert.x: most requests have neither a suffix nor a
        // format parameter, and then no URI is built or query parsed.
        String path = routing.request().path();
        if (path == null) return;
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash + 1 || dot == path.length() - 1) {
            String query = routing.request().query();
            String format = query != null && query.contains("format=") ? routing.queryParams().get("format") : null;
            if (format != null) accept(context, format);
            return;
        }
        String format = path.substring(dot + 1);
        if (format.contains("?")) return;
        RequestState.of(routing).format = format;
        if (path.startsWith("/rails/active_storage/")) return;
        accept(context, format);
        URI uri = context.getUriInfo().getRequestUri();
        context.setRequestUri(uri.resolve(path.substring(0, dot) + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())));
    }

    private static void accept(ContainerRequestContext context, String format) {
        String type = Formats.typeOfExtension(format);
        if (type != null) context.getHeaders().putSingle(HttpHeaders.ACCEPT, type);
    }

    /// HTML forms send PUT, PATCH and DELETE as a POST with a `_method` field, which wins over an
    /// `X-HTTP-Method-Override` header, as in `Rack::MethodOverride`. The field is in the body, which
    /// Quarkus REST reads only after matching, so a form POST's body is read here, off the event
    /// loop, and handed on as the entity: all of a URL-encoded one, and of a multipart one the start,
    /// where Rails' forms put `_method`.
    @ServerRequestFilter(preMatching = true)
    public @Nullable Uni<Void> methodOverride(ContainerRequestContext context) {
        if (!context.getMethod().equals("POST")) return null;
        MediaType type = context.getMediaType();
        boolean urlEncoded = type != null && type.isCompatible(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
        boolean multipart = type != null && type.isCompatible(MediaType.MULTIPART_FORM_DATA_TYPE);
        if (!urlEncoded && !multipart) {
            override(context, null);
            return null;
        }
        return Uni.createFrom().<Void>item(() -> {
            try {
                InputStream body = context.getEntityStream();
                byte[] read = urlEncoded ? body.readAllBytes() : body.readNBytes(MULTIPART_PREFIX);
                String field = urlEncoded ? MethodOverride.fromForm(read) : MethodOverride.fromMultipart(read, type.getParameters().get("boundary"));
                context.setEntityStream(urlEncoded ? new ByteArrayInputStream(read) : new SequenceInputStream(new ByteArrayInputStream(read), body));
                override(context, field);
                return null;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    /// How much of a multipart body is searched for `_method`.
    private static final int MULTIPART_PREFIX = 16 * 1024;

    private static void override(ContainerRequestContext context, @Nullable String field) {
        String method = field != null ? field : context.getHeaderString("X-HTTP-Method-Override");
        if (method == null) return;
        method = method.toUpperCase(Locale.ROOT);
        if (method.equals("PUT") || method.equals("PATCH") || method.equals("DELETE")) context.setMethod(method);
    }

    @ServerRequestFilter
    public @Nullable Response check(ContainerRequestContext context, ResourceInfo resource, RoutingContext routing) {
        RequestState state = RequestState.of(routing);
        if (has(resource, Access.Framework.class)) return null;
        state.dataVersionAtStart = db.dataVersion();

        HttpServerRequest request = routing.request();
        boolean isPublic = has(resource, Access.Public.class);
        boolean guestOnly = has(resource, Access.GuestOnly.class);
        if (!isGet(request) && db.read(conn -> Ban.banned(conn, origin.remoteIp()))) return Responses.head(429);
        if (!isPublic && !guestOnly && !authentication.resume() && !authentication.authenticateBot()) {
            railsSession.set("return_to_after_authenticating", origin.requestUrl());
            return Responses.redirect(origin.url(Paths.NEW_SESSION));
        }
        boolean bot = viewer.authenticatedByBot();
        if (bot && !has(resource, Access.BotsAllowed.class)) return Responses.head(403);
        if (!bot && !has(resource, Access.NoForgeryProtection.class) && !sameOriginWrite(request, origin)) return Responses.head(422);
        if (AllowBrowser.blocked(request.getHeader("user-agent"))) return pages.incompatibleBrowser(ownLayout(Objects.requireNonNullElse(request.path(), "/")));
        if (guestOnly && authentication.resume()) return Responses.redirect(origin.url(Paths.ROOT));
        return null;
    }

    private static boolean isGet(HttpServerRequest request) {
        return request.method() == HttpMethod.GET || request.method() == HttpMethod.HEAD;
    }

    private static boolean has(ResourceInfo resource, Class<? extends Annotation> annotation) {
        return resource.getResourceMethod().isAnnotationPresent(annotation) || resource.getResourceClass().isAnnotationPresent(annotation);
    }

    /// Cross-site request forgery protection without tokens: writes must come from this origin by
    /// the browser's own account (`Sec-Fetch-Site: same-origin` or `same-site`, and a matching
    /// `Origin` if one is sent). Clients that send neither header are only trusted over plain HTTP.
    public static boolean sameOriginWrite(HttpServerRequest request, RequestOrigin requestOrigin) {
        if (isGet(request)) return true;
        String origin = request.getHeader("origin");
        if (origin != null && (origin.equals("null") || !sameOrigin(origin, requestOrigin))) return false;
        String site = request.getHeader("sec-fetch-site");
        if (site == null) return !requestOrigin.ssl();
        return switch (site.toLowerCase(Locale.ROOT)) {
            case "same-origin", "same-site" -> true;
            default -> false;
        };
    }

    private static boolean sameOrigin(String origin, RequestOrigin requestOrigin) {
        try {
            URI uri = URI.create(origin);
            String actual = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
            return actual.equalsIgnoreCase(requestOrigin.baseUrl());
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
