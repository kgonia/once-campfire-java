package campfire.cable;

import java.util.Map;

/// `ActionCable::Channel::Base`: one instance per subscription, driven by its connection one
/// callback at a time.
///
/// An exception escaping a callback is what a Ruby exception is to `Subscriptions#execute_command`:
/// logged, and nothing is sent. A subscription whose `subscribed` threw is neither confirmed nor
/// rejected, and stays registered. Rails' `on_subscribe`/`on_unsubscribe` callbacks run after
/// `subscribed`/`unsubscribed`, so they go at the end of these methods.
interface Channel {
    default void subscribed(Subscription sub) throws Exception {}

    /// Also runs for a rejected subscription, as in Rails (`reject_subscription` removes the
    /// subscription, which unsubscribes it).
    default void unsubscribed(Subscription sub) throws Exception {}

    /// Dispatches a `perform` from the client; false when `action` isn't one of the channel's
    /// public methods ("Unable to process"). `action` is `data["action"]`, or `receive` when that's
    /// blank. Ruby makes every public method an action, including a redefined `subscribed`.
    default boolean perform(String action, Map<String, Object> data, Subscription sub) throws Exception {
        return false;
    }

    /// `ApplicationCable::Channel` and `HeartbeatChannel`: subscribe, confirm, nothing else.
    final class Empty implements Channel {}
}
