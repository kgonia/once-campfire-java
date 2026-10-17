package campfire.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// How a resource method may be reached. Unannotated methods require a signed-in user (a session
/// cookie; bot keys are refused) and pass the cross-site write check. See `AccessFilter`.
public final class Access {
    private Access() {}

    /// Anyone; a session cookie is ignored.
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Public {}

    /// Only signed-out visitors; signed-in users are sent to the root page.
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface GuestOnly {}

    /// A bot's `bot_key` path segment authenticates too, and such requests skip the cross-site check.
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface BotsAllowed {}

    /// One of Rails' own controllers rather than the app's: no sign-in, ban, cross-site or browser
    /// checks, and no version headers. The resource does whatever checking its controller does.
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Framework {}

    /// Skips the cross-site write check (the PWA files are fetched by the browser itself).
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface NoForgeryProtection {}
}
