package campfire.sessions;

import campfire.cable.Cable;
import campfire.db.Database;
import campfire.db.Times;
import campfire.users.User;
import campfire.web.Cookies;
import campfire.web.Paths;
import campfire.web.RailsSession;
import campfire.web.RequestOrigin;
import io.quarkus.logging.Log;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jspecify.annotations.Nullable;

/// Signing in and out. A browser holds a signed `session_token` cookie naming a row in `sessions`;
/// bots authenticate per request with the `bot_key` path segment instead. {@link SessionMechanism}
/// finds who the request names; this signs the {@link Viewer} in as them.
@ApplicationScoped
public class Authentication {
    @Inject
    Database db;
    @Inject
    SessionLookup sessions;
    @Inject
    CurrentIdentityAssociation identity;
    @Inject
    Viewer viewer;
    @Inject
    Cookies cookies;
    @Inject
    RailsSession railsSession;
    @Inject
    RequestOrigin origin;
    @Inject
    RoutingContext routing;
    /// Through a provider: the cable server needs the app, which needs this class's dependencies.
    @Inject
    Provider<Cable> cable;

    private @Nullable SignedIn signedIn() {
        return identity.getIdentity().getPrincipal() instanceof SignedIn signedIn ? signedIn : null;
    }

    /// Signs the request in from its session cookie, if that names a live session. Activity is
    /// recorded at most hourly (see `Session.needsResume`), and the cookie is re-signed then too,
    /// which keeps its twenty-year expiry rolling.
    public boolean resume() {
        SignedIn found = signedIn();
        Session session = found == null ? null : found.session();
        if (session == null) return false;
        if (session.needsResume(Times.now())) {
            String userAgent = userAgent();
            String ip = origin.remoteIp();
            db.write(tx -> {
                Session.resume(tx, session.id(), userAgent, ip);
                return null;
            });
            sessions.forget(session.token());
            cookies.setSignedPermanent(Cookies.SESSION_TOKEN, session.token());
        }
        viewer.signIn(session, found.user());
        return true;
    }

    /// Whether the session cookie names a live session, without resuming it (what Active Storage's
    /// upload endpoints ask).
    public boolean hasSession() {
        SignedIn found = signedIn();
        return found != null && found.session() != null;
    }

    /// Signs a bot in from its `bot_key` (`<id>-<token>`), surrounding whitespace ignored.
    public boolean authenticateBot() {
        SignedIn found = signedIn();
        if (found == null || found.session() != null) return false;
        viewer.signInBot(found.user());
        return true;
    }

    /// A new session row and its cookie.
    public Session start(User user) {
        String userAgent = userAgent();
        String ip = origin.remoteIp();
        Session session = db.write(tx -> Session.start(tx, user.id(), userAgent, ip));
        cookies.setSignedPermanent(Cookies.SESSION_TOKEN, session.token());
        viewer.signIn(session, user);
        return session;
    }

    /// Deletes the session and its cookie, empties the Rails session, and drops the user's open
    /// sockets (they authenticated with that session). Socket errors are only logged.
    public void terminate() {
        Session session = viewer.session();
        User user = viewer.userIfSignedIn();
        if (session != null) {
            db.write(tx -> {
                Session.delete(tx, session.id());
                return null;
            });
            sessions.forget(session.token());
        }
        railsSession.reset();
        cookies.delete(Cookies.SESSION_TOKEN);
        viewer.signOut();
        if (user != null) {
            try {
                cable.get().disconnectUser(user.id());
            } catch (RuntimeException e) {
                Log.warn("Could not disconnect remote connections on sign out", e);
            }
        }
    }

    /// Where sign-in continues: the page that sent the visitor to sign in, else the root.
    public String afterSignIn() {
        Object stored = railsSession.get("return_to_after_authenticating");
        railsSession.set("return_to_after_authenticating", null);
        if (stored == null) return origin.url(Paths.ROOT);
        // A full URL as stored here; a path resolves against this request, as `redirect_to` does.
        String location = stored.toString();
        return location.startsWith("/") ? origin.url(location) : location;
    }

    private @Nullable String userAgent() {
        return routing.request().getHeader("user-agent");
    }
}
