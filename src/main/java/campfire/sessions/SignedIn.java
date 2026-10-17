package campfire.sessions;

import campfire.db.Database;
import campfire.users.User;
import campfire.web.RequestState;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.Principal;
import org.jspecify.annotations.Nullable;

/// The principal of a signed-in request: a browser session and its user, or a bot, which has no
/// session.
public record SignedIn(@Nullable Session session, User user) implements Principal {
    @Override
    public String getName() {
        return Long.toString(user.id());
    }

    private static SecurityIdentity identity(@Nullable Session session, User user) {
        return QuarkusSecurityIdentity.builder().setPrincipal(new SignedIn(session, user)).build();
    }

    /// A session remembered by {@link SessionLookup} is answered at once; only a miss reads the
    /// database, off the calling thread.
    @ApplicationScoped
    public static class Sessions implements IdentityProvider<SessionMechanism.SessionToken> {
        @Inject
        SessionLookup sessions;
        @Inject
        Database db;

        @Override
        public Class<SessionMechanism.SessionToken> getRequestType() {
            return SessionMechanism.SessionToken.class;
        }

        @Override
        public Uni<SecurityIdentity> authenticate(SessionMechanism.SessionToken request, AuthenticationRequestContext context) {
            RoutingContext routing = HttpSecurityUtils.getRoutingContextAttribute(request);
            long version = routing == null ? -1 : RequestState.dataVersionAtStart(routing);
            if (version >= 0) {
                Session.WithUser cached = sessions.cached(request.token, version);
                if (cached != null) return Uni.createFrom().item(identity(cached.session(), cached.user()));
            }
            return context.runBlocking(() -> {
                Session.WithUser found = sessions.find(request.token, version >= 0 ? version : db.dataVersion());
                return found == null ? null : identity(found.session(), found.user());
            });
        }
    }

    @ApplicationScoped
    public static class Bots implements IdentityProvider<SessionMechanism.BotKey> {
        @Inject
        Database db;

        @Override
        public Class<SessionMechanism.BotKey> getRequestType() {
            return SessionMechanism.BotKey.class;
        }

        @Override
        public Uni<SecurityIdentity> authenticate(SessionMechanism.BotKey request, AuthenticationRequestContext context) {
            return context.runBlocking(() -> {
                User bot = db.read(conn -> User.authenticateBot(conn, request.key));
                return bot == null ? null : identity(null, bot);
            });
        }
    }
}
