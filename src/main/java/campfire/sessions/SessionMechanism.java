package campfire.sessions;

import campfire.rails.Secrets;
import campfire.web.Cookies;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.identity.request.BaseAuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/// Who a request comes from, as the Rails app decides it: the signed `session_token` cookie names
/// a row in `sessions`, and failing that, on the bot API, the `bot_key` path segment names a bot.
/// Runs only when something asks (`quarkus.http.auth.proactive=false`), which `Authentication`
/// does for the routes that need a user.
@ApplicationScoped
public class SessionMechanism implements HttpAuthenticationMechanism {
    /// `/rooms/:room_id/:bot_key/messages...`, the only routes with a `bot_key`.
    private static final Pattern BOT_ROUTE = Pattern.compile("/rooms/[^/]+/([^/]+)/messages(?:[/.].*)?");

    @Inject
    Secrets secrets;

    /// A verified `session_token` cookie's value.
    public static final class SessionToken extends BaseAuthenticationRequest {
        final String token;

        SessionToken(String token) {
            this.token = token;
        }
    }

    public static final class BotKey extends BaseAuthenticationRequest {
        final String key;

        BotKey(String key) {
            this.key = key;
        }
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identities) {
        String token = Cookies.verify(secrets, Cookies.SESSION_TOKEN, Cookies.incoming(context.request(), Cookies.SESSION_TOKEN));
        Uni<SecurityIdentity> session = token == null ? Uni.createFrom().nullItem() : attempt(identities, new SessionToken(token), context);
        String botKey = botKey(context.normalizedPath());
        return botKey == null ? session : session.onItem().ifNull().switchTo(() -> attempt(identities, new BotKey(botKey), context));
    }

    private static @Nullable String botKey(String path) {
        Matcher route = BOT_ROUTE.matcher(path);
        if (!route.matches()) return null;
        String key = route.group(1).strip();
        return key.isEmpty() ? null : key;
    }

    /// A token or key that names nobody leaves the request signed out, not failed.
    private static Uni<SecurityIdentity> attempt(IdentityProviderManager identities, AuthenticationRequest request, RoutingContext context) {
        return identities.authenticate(HttpSecurityUtils.setRoutingContextAttribute(request, context))
            .onFailure(AuthenticationFailedException.class).recoverWithNull();
    }

    /// The access filter redirects to sign-in itself.
    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().nullItem();
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of(SessionToken.class, BotKey.class);
    }
}
