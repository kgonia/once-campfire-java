package campfire.sessions;

import campfire.users.User;
import org.jspecify.annotations.Nullable;

/// Who a request comes from: a browser session and its user, or a bot, which has no session.
record SignedIn(@Nullable Session session, User user) {}
