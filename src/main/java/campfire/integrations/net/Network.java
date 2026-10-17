package campfire.integrations.net;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.net.TrustOptions;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;

/// What the outgoing HTTP clients share: name resolution, where a connection to a vetted address
/// goes, and the Vert.x client (with its TLS trust). {@link #system} in production; tests
/// substitute fixed DNS answers, a route that sends fake public addresses to a local server, and
/// their own CA. The client uses no proxy: an egress proxy would re-resolve the host and defeat
/// the pinning.
public record Network(Resolver resolver, UnaryOperator<InetSocketAddress> route, Vertx vertx, HttpClient http) {
    /// Name resolution: `getaddrinfo` in production (Ruby's `Resolv.getaddresses` reads
    /// /etc/hosts, then DNS).
    @FunctionalInterface
    public interface Resolver {
        List<InetAddress> lookup(String host) throws IOException;
    }

    public static final Resolver SYSTEM_RESOLVER = host -> Arrays.asList(InetAddress.getAllByName(host));

    /// Bounds the TCP connect behind the callers' own (shorter) open timeouts.
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    /// Response heads Netty accepts; its 8 KB default turns away pages with large cookies or CSPs.
    private static final int MAX_HEAD_BYTES = 128 * 1024;
    /// Connections at once to one pinned address and host name; more wait their turn.
    private static final int MAX_CONNECTIONS_PER_ENDPOINT = 256;

    /// `trust` null is the JDK's default trust store.
    public static Network create(Vertx vertx, Resolver resolver, UnaryOperator<InetSocketAddress> route, TrustOptions trust) {
        var options = new HttpClientOptions()
            // Every exchange connects to the address vetted for it, so a connection is never reused.
            .setKeepAlive(false)
            .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
            .setMaxHeaderSize(MAX_HEAD_BYTES)
            .setTrustOptions(trust);
        var pool = new PoolOptions().setHttp1MaxSize(MAX_CONNECTIONS_PER_ENDPOINT);
        return new Network(resolver, route, vertx, vertx.createHttpClient(options, pool));
    }

    /// The system resolver, real addresses, and the JDK's default trust store.
    public static Network system(Vertx vertx) {
        return create(vertx, SYSTEM_RESOLVER, UnaryOperator.identity(), null);
    }
}
