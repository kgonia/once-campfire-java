package campfire.sessions;

import campfire.users.User;
import jakarta.enterprise.context.RequestScoped;
import org.jspecify.annotations.Nullable;

/// Who the request is signed in as (Rails' `Current.user` and `Current.session`), set by
/// {@link Authentication}.
@RequestScoped
public class Viewer {
    private @Nullable User user;
    private @Nullable Session session;
    private boolean bot;

    /// The signed-in user (or bot). Only for resources behind sign-in, which is all but the
    /// `Access.Public` and `Access.GuestOnly` ones.
    public User user() {
        if (user == null) throw new IllegalStateException("not signed in");
        return user;
    }

    public @Nullable User userIfSignedIn() {
        return user;
    }

    /// The browser session; null for guests and bots.
    public @Nullable Session session() {
        return session;
    }

    public boolean authenticatedByBot() {
        return bot;
    }

    void signIn(Session session, User user) {
        this.session = session;
        this.user = user;
        this.bot = false;
    }

    void signInBot(User bot) {
        this.session = null;
        this.user = bot;
        this.bot = true;
    }

    void signOut() {
        this.session = null;
        this.user = null;
    }
}
