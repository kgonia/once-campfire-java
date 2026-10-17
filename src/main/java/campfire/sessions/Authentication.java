package campfire.sessions;

import campfire.cable.Cable;
import campfire.db.Database;
import campfire.db.Times;
import campfire.users.User;
import campfire.web.CurrentRequest;
import campfire.web.Paths;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

/// Signing in and out. A browser holds a signed `session_token` cookie naming a row in `sessions`;
/// bots authenticate per request with the `bot_key` path segment instead.
@ApplicationScoped
public class Authentication {
    @Inject
    Database db;
    @Inject
    SessionLookup sessions;
    /// Through a provider: the cable server needs the app, which needs this class's dependencies.
    @Inject
    Provider<Cable> cable;

    /// Signs the request in from its session cookie, if that names a live session. Activity is
    /// recorded at most hourly (see `Session.needsResume`), and the cookie is re-signed then too,
    /// which keeps its twenty-year expiry rolling.
    public boolean resume(CurrentRequest request) {
        String token = request.signedCookie(CurrentRequest.SESSION_TOKEN_COOKIE);
        if (token == null) return false;
        Session.WithUser found = sessions.find(token);
        if (found == null) return false;
        Session session = found.session();
        if (session.needsResume(Times.now())) {
            String userAgent = request.userAgent();
            String ip = request.remoteIp();
            db.write(tx -> {
                Session.resume(tx, session.id(), userAgent, ip);
                return null;
            });
            sessions.forget(token);
            request.setSignedPermanentCookie(CurrentRequest.SESSION_TOKEN_COOKIE, session.token());
        }
        request.signIn(session, found.user());
        return true;
    }

    /// Whether the session cookie names a live session, without resuming it (what Active Storage's
    /// upload endpoints ask).
    public boolean hasSession(CurrentRequest request) {
        String token = request.signedCookie(CurrentRequest.SESSION_TOKEN_COOKIE);
        return token != null && sessions.find(token) != null;
    }

    /// Signs a bot in from its `bot_key` (`<id>-<token>`), surrounding whitespace ignored.
    public boolean authenticateBot(CurrentRequest request) {
        String botKey = request.params().present("bot_key");
        if (botKey == null) return false;
        User bot = db.read(conn -> User.authenticateBot(conn, botKey.strip()));
        if (bot == null) return false;
        request.signInBot(bot);
        return true;
    }

    /// A new session row and its cookie.
    public Session start(CurrentRequest request, User user) {
        String userAgent = request.userAgent();
        String ip = request.remoteIp();
        Session session = db.write(tx -> Session.start(tx, user.id(), userAgent, ip));
        request.setSignedPermanentCookie(CurrentRequest.SESSION_TOKEN_COOKIE, session.token());
        request.signIn(session, user);
        return session;
    }

    /// Deletes the session and its cookie, empties the Rails session, and drops the user's open
    /// sockets (they authenticated with that session). Socket errors are only logged.
    public void terminate(CurrentRequest request) {
        Session session = request.session();
        User user = request.userIfSignedIn();
        if (session != null) {
            db.write(tx -> {
                Session.delete(tx, session.id());
                return null;
            });
            sessions.forget(session.token());
        }
        request.resetSession();
        request.deleteCookie(CurrentRequest.SESSION_TOKEN_COOKIE);
        request.signOut();
        if (user != null) {
            try {
                cable.get().disconnectUser(user.id());
            } catch (RuntimeException e) {
                Log.warn("Could not disconnect remote connections on sign out", e);
            }
        }
    }

    /// Where sign-in continues: the page that sent the visitor to sign in, else the root.
    public String afterSignIn(CurrentRequest request) {
        Object stored = request.sessionValue("return_to_after_authenticating");
        request.setSessionValue("return_to_after_authenticating", null);
        return stored == null ? request.url(Paths.ROOT) : stored.toString();
    }
}
