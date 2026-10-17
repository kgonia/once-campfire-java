package campfire.integrations.webpush;

import campfire.integrations.net.Guard;
import campfire.integrations.net.Network;
import campfire.integrations.net.PinnedHttp;
import campfire.integrations.net.PinnedHttp.Header;
import campfire.integrations.net.PinnedHttp.HttpException;
import campfire.integrations.net.Url;
import campfire.rails.Json;
import io.quarkus.logging.Log;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/// Web Push delivery: `WebPush::Notification` and `WebPush::Pool` (reference/lib/web_push), the
/// web-push 3.1.0 gem request they make (with the `WebPush::PersistentRequest` patch in
/// reference/config/initializers/web_push.rb), and the endpoint checks of `Push::Subscription`
/// (reference/app/models/push/subscription.rb).
///
/// Delivery resolves the endpoint on the worker, only for a permitted `https://…:443` push
/// service, through the private network guard, and connects to that address with no proxy.
/// As in the Rust port: invalid VAPID keys turn push off at boot; subscriptions are invalidated
/// only on 404/410 or an invalid subscription P-256 key (not on TLS or configuration failures);
/// titles are cut to 256 bytes and bodies to 3 KB (with an ellipsis) so the message fits a push
/// record; deliveries time out after 10 seconds per connect/read and 30 overall.
public final class WebPush implements AutoCloseable {
    /// `WebPush::Notification#vapid_identification`'s default subject.
    public static final String DEFAULT_SUBJECT = "mailto:support@37signals.com";

    /// `Push::Subscription::PERMITTED_ENDPOINT_HOSTS`
    public static final List<String> PERMITTED_ENDPOINT_HOSTS = List.of("jmt17.google.com", "fcm.googleapis.com",
        "updates.push.services.mozilla.com", "web.push.apple.com", "notify.windows.com");

    /// `Rails.application.routes.url_helpers.account_logo_path`
    public static final String ACCOUNT_LOGO_PATH = "/account/logo";

    /// How long a title and body may be, counted as the bytes they take in the JSON message. An
    /// encrypted record holds at most 4096 bytes: 4078 of JSON once the padding and tag are in,
    /// and the icon, path and badge take under 150 of that.
    public static final int MAX_TITLE_BYTES = 256;
    public static final int MAX_BODY_BYTES = 3072;

    /// `WebPush::Request#default_options[:ttl]`: four weeks.
    static final long TTL_SECONDS = 60L * 60 * 24 * 7 * 4;
    private static final PinnedHttp.Timeouts TIMEOUTS = new PinnedHttp.Timeouts(Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(30));

    /// `Concurrent::ThreadPoolExecutor.new(max_threads: 50, max_queue: 10000)`
    static final int MAX_THREADS = 50;
    static final int MAX_QUEUE = 10_000;

    /// A row of `push_subscriptions`, as delivery needs it.
    public record Subscription(long id, String endpoint, String p256dh, String auth) {}

    /// What a delivery came to.
    public sealed interface Result {
        /// The push service accepted it (a 2xx).
        record Delivered(int status) implements Result {}

        /// Nothing was sent: the endpoint isn't a permitted push service or doesn't resolve to a
        /// public address (`WebPush::Notification#deliver` returns nil).
        record Skipped() implements Result {}

        /// What `WebPush.payload_send` raised.
        record Failed(DeliveryException error) implements Result {}
    }

    /// A failed delivery.
    public static final class DeliveryException extends Exception {
        public enum Kind {
            /// 404 or 410: the push service doesn't know the subscription (any more).
            SUBSCRIPTION_GONE,
            /// Any other status but a 2xx.
            RESPONSE,
            /// The subscription's key isn't a P-256 point.
            INVALID_SUBSCRIPTION_KEY,
            /// Blank or malformed keys, an oversized payload.
            ARGUMENT,
            /// The TLS handshake failed, which may well be our side's fault (a clock or CA store
            /// problem).
            TLS,
            /// Connection failures and timeouts.
            HTTP
        }

        public final Kind kind;
        /// The push service's status, for response errors; 0 otherwise.
        public final int status;

        DeliveryException(Kind kind, String message, int status, Throwable cause) {
            super(message, cause, false, false);
            this.kind = kind;
            this.status = status;
        }

        /// Whether the subscription can never be delivered to, so the pool invalidates it.
        /// Rails' pool also does for any `OpenSSL::OpenSSLError` (TLS failures, a bad VAPID key);
        /// those say nothing about the subscription, and a 404 does (RFC 8030, section 7.3).
        public boolean invalidatesSubscription() {
            return kind == Kind.SUBSCRIPTION_GONE || kind == Kind.INVALID_SUBSCRIPTION_KEY;
        }
    }

    private final Network net;
    private final Vapid vapid;
    private final Semaphore running = new Semaphore(MAX_THREADS);
    /// Deliveries running or waiting; one is held until a delivery finishes.
    private final Semaphore slots = new Semaphore(MAX_THREADS + MAX_QUEUE);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicBoolean shutDown = new AtomicBoolean();
    private final ExecutorService deliveries = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("web-push-", 0).factory());
    /// `invalidation_pool`: one worker, so invalidations run in order.
    private final ExecutorService invalidations = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("web-push-invalidation").factory());
    private volatile LongConsumer invalidSubscriptionHandler;

    /// Missing or invalid keys log a warning and leave push off ({@link #enabled()} is false);
    /// nothing is delivered.
    public WebPush(String publicKey, String privateKey, String subject, Network net) {
        this.net = net;
        Vapid parsed = null;
        try {
            parsed = Vapid.of(subject == null || subject.isBlank() ? DEFAULT_SUBJECT : subject, publicKey, privateKey);
        } catch (Vapid.VapidException e) {
            Log.warn("Web Push is off: " + e.getMessage());
        }
        this.vapid = parsed;
    }

    /// Whether the VAPID key pair is usable.
    public boolean enabled() {
        return vapid != null;
    }

    /// `Push::Subscription.find_by(id:)&.destroy`, run on the pool's own (single) worker, so it
    /// may block.
    public void setInvalidSubscriptionHandler(LongConsumer handler) {
        this.invalidSubscriptionHandler = handler;
    }

    // --- Messages ---------------------------------------------------------------------------

    /// `WebPush::Notification#encoded_message`: `JSON.generate` (plain JSON, no HTML escaping),
    /// with the title and body cut to fit a push message.
    public static String encodedMessage(String title, String body, String path, long badge, String iconPath) {
        var data = new LinkedHashMap<String, Object>();
        data.put("path", path);
        data.put("badge", badge);
        var options = new LinkedHashMap<String, Object>();
        options.put("body", truncateJsonString(body, MAX_BODY_BYTES));
        options.put("icon", iconPath);
        options.put("data", data);
        var message = new LinkedHashMap<String, Object>();
        message.put("title", truncateJsonString(title, MAX_TITLE_BYTES));
        message.put("options", options);
        return Json.generate(message);
    }

    /// `text`, cut short with an ellipsis if it takes more than `maxBytes` as a JSON string's
    /// contents.
    static String truncateJsonString(String text, int maxBytes) {
        if (text == null) return null;
        long total = 0;
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            total += jsonLength(c);
            i += Character.charCount(c);
        }
        if (total <= maxBytes) return text;
        String ellipsis = "…";
        long used = 3;
        int cut = text.length();
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            used += jsonLength(c);
            if (used > maxBytes) {
                cut = i;
                break;
            }
            i += Character.charCount(c);
        }
        return text.substring(0, cut) + ellipsis;
    }

    /// The bytes `c` takes in a JSON string: `"`, `\` and the short escapes take two, other
    /// control characters six (`\u001f`).
    private static int jsonLength(int c) {
        return switch (c) {
            case '"', '\\', '\n', '\r', '\t', '\b', '\f' -> 2;
            default -> c < 0x20 ? 6 : c < 0x80 ? 1 : c < 0x800 ? 2 : c < 0x10000 ? 3 : 4;
        };
    }

    // --- Endpoint checks (Push::Subscription) ------------------------------------------------

    /// `Push::Subscription#validate_endpoint_url` (with `validates :endpoint, presence: true`):
    /// every validation error for `endpoint`, in the order Rails adds them.
    public static List<String> endpointErrors(String endpoint, Network.Resolver resolver) {
        var errors = new ArrayList<String>();
        if (endpoint == null || campfire.richtext.Html.isBlank(endpoint)) errors.add("can't be blank");
        Url uri = endpointUri(endpoint);
        if (uri == null) {
            errors.add("is not a valid URL");
        } else if (!uri.isHttps()) {
            errors.add("must use HTTPS");
        } else if (uri.port() != 443) {
            errors.add("must use the default HTTPS port");
        } else if (!permittedEndpointHost(uri)) {
            errors.add("is not a permitted push service");
        } else if (resolvedEndpointIp(endpoint, resolver) == null) {
            errors.add("resolves to a private or invalid IP address");
        }
        return errors;
    }

    /// `Push::Subscription#resolved_endpoint_ip`: validated at the point of use, not just when
    /// saved. Null when the endpoint isn't a permitted push service or has no public address.
    static InetAddress resolvedEndpointIp(String endpoint, Network.Resolver resolver) {
        Url uri = endpointUri(endpoint);
        if (uri == null || !uri.isHttps() || uri.port() != 443 || !permittedEndpointHost(uri)) return null;
        try {
            return Guard.resolve(resolver, uri.host());
        } catch (Guard.GuardException e) {
            return null;
        }
    }

    /// `endpoint_uri`: `URI.parse(endpoint) if endpoint.present?`, nil on `URI::InvalidURIError`.
    private static Url endpointUri(String endpoint) {
        if (endpoint == null || campfire.richtext.Html.isBlank(endpoint)) return null;
        return Url.parseOrNull(endpoint);
    }

    private static boolean permittedEndpointHost(Url uri) {
        if (uri.host() == null || uri.host().isEmpty()) return false;
        String host = uri.host().toLowerCase(Locale.ROOT);
        for (String permitted : PERMITTED_ENDPOINT_HOSTS) {
            if (host.equals(permitted) || host.endsWith("." + permitted)) return true;
        }
        return false;
    }

    // --- Delivery ---------------------------------------------------------------------------

    /// `WebPush::Notification#deliver`, now: what the test-notification action does
    /// (`Users::PushSubscriptions::TestNotificationsController#create`, which lets errors raise).
    /// A failure here never invalidates the subscription.
    public Result deliverNow(Subscription subscription, String title, String body, String path, long badge, String iconPath) {
        if (vapid == null) return new Result.Failed(new DeliveryException(DeliveryException.Kind.ARGUMENT, "Web Push is off", 0, null));
        InetAddress ip = resolvedEndpointIp(subscription.endpoint(), net.resolver());
        if (ip == null) return new Result.Skipped();
        try {
            return new Result.Delivered(payloadSend(subscription, ip, encodedMessage(title, body, path, badge, iconPath), System.currentTimeMillis() / 1000));
        } catch (DeliveryException e) {
            return new Result.Failed(e);
        }
    }

    /// `WebPush::Pool#queue` for one subscription: delivered on the pool, at most 50 at once and
    /// 10,000 waiting (more are dropped, like `Concurrent::RejectedExecutionError`, and logged).
    /// Invalid subscriptions go to the {@link #setInvalidSubscriptionHandler handler}.
    public void deliverLater(Subscription subscription, String title, String body, String path, long badge, String iconPath) {
        if (vapid == null) return;
        if (shutDown.get()) {
            Log.warn("WebPush::Pool is shut down, dropping a notification");
            return;
        }
        if (!slots.tryAcquire()) {
            if (dropped.getAndIncrement() == 0) Log.error("WebPush::Pool is full, dropping notifications");
            return;
        }
        long wasDropped = dropped.getAndSet(0);
        if (wasDropped > 0) Log.error("WebPush::Pool dropped " + wasDropped + " notifications while it was full");
        String message = encodedMessage(title, body, path, badge, iconPath);
        try {
            deliveries.execute(() -> {
                try {
                    running.acquire();
                    try {
                        deliver(subscription, message);
                    } finally {
                        running.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException | Error e) {
                    Log.error("Error in WebPush::Pool.deliver: " + e.getClass().getName() + " " + e.getMessage(), e);
                } finally {
                    slots.release();
                }
            });
        } catch (RejectedExecutionException e) {
            slots.release();
        }
    }

    /// `WebPush::Pool#deliver`
    private void deliver(Subscription subscription, String message) {
        InetAddress ip = resolvedEndpointIp(subscription.endpoint(), net.resolver());
        if (ip == null) return;
        try {
            payloadSend(subscription, ip, message, System.currentTimeMillis() / 1000);
        } catch (DeliveryException e) {
            if (e.invalidatesSubscription()) {
                invalidateSubscriptionLater(subscription.id());
            } else {
                Log.error("Error in WebPush::Pool.deliver: " + e.kind + " " + e.getMessage());
            }
        }
    }

    private void invalidateSubscriptionLater(long id) {
        LongConsumer handler = invalidSubscriptionHandler;
        if (handler == null) return;
        try {
            invalidations.execute(() -> {
                Log.info("Destroying push subscription: " + id);
                try {
                    handler.accept(id);
                } catch (RuntimeException e) {
                    Log.error("Error in WebPush::Pool.invalid_subscription_handler: " + e.getClass().getName() + " " + e.getMessage(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            // shut down
        }
    }

    /// `WebPush.payload_send(message:, endpoint:, endpoint_ip:, p256dh:, auth:, vapid:,
    /// urgency: "high")` through the pinned-address branch of `WebPush::PersistentRequest`.
    int payloadSend(Subscription subscription, InetAddress endpointIp, String message, long nowSeconds) throws DeliveryException {
        Url uri = Url.parseOrNull(subscription.endpoint());
        if (uri == null || uri.host() == null) {
            throw new DeliveryException(DeliveryException.Kind.ARGUMENT, "bad URI: " + subscription.endpoint(), 0, null);
        }
        String host = uri.host();
        byte[] payload;
        try {
            payload = Encryption.encrypt(message.getBytes(StandardCharsets.UTF_8), subscription.p256dh(), subscription.auth());
        } catch (Encryption.EncryptionException e) {
            throw e.invalidKey
                ? new DeliveryException(DeliveryException.Kind.INVALID_SUBSCRIPTION_KEY, e.getMessage(), 0, e)
                : new DeliveryException(DeliveryException.Kind.ARGUMENT, e.getMessage(), 0, e);
        }

        var headers = new ArrayList<Header>();
        headers.add(new Header("Content-Type", "application/octet-stream"));
        headers.add(new Header("Ttl", Long.toString(TTL_SECONDS)));
        headers.add(new Header("Urgency", "high"));
        headers.add(new Header("Content-Encoding", "aes128gcm"));
        headers.add(new Header("Authorization", vapid.authorization(uri.scheme() + "://" + host, nowSeconds)));

        int port = uri.port() == -1 ? 443 : Math.min(uri.port(), 65535);
        var target = new PinnedHttp.Target(true, host, port, endpointIp);
        var request = new PinnedHttp.Request("POST", uri.requestUri(), headers, payload);
        try (var response = PinnedHttp.exchange(net, target, request, TIMEOUTS)) {
            return verifyResponse(response.status, host);
        } catch (HttpException e) {
            var kind = e.kind == HttpException.Kind.TLS ? DeliveryException.Kind.TLS : DeliveryException.Kind.HTTP;
            throw new DeliveryException(kind, e.getMessage(), 0, e);
        }
    }

    /// `WebPush::Request#verify_response`: the push service's status.
    static int verifyResponse(int status, String host) throws DeliveryException {
        if (status >= 200 && status <= 299) return status;
        var kind = status == 404 || status == 410 ? DeliveryException.Kind.SUBSCRIPTION_GONE : DeliveryException.Kind.RESPONSE;
        throw new DeliveryException(kind, "host: " + host + ", status: " + status, status, null);
    }

    /// Deliveries queued or running.
    int pending() {
        return MAX_THREADS + MAX_QUEUE - slots.availablePermits();
    }

    /// `WebPush::Pool#shutdown`: waits up to a second for queued deliveries, then stops the
    /// invalidation worker once it has drained.
    public void shutdown() {
        if (!shutDown.compareAndSet(false, true)) return;
        try {
            if (slots.tryAcquire(MAX_THREADS + MAX_QUEUE, 1, TimeUnit.SECONDS)) slots.release(MAX_THREADS + MAX_QUEUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        deliveries.shutdownNow();
        invalidations.shutdown();
        try {
            invalidations.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        shutdown();
    }
}
