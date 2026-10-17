package campfire.integrations.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLContext;

/// What the outgoing HTTP clients share: name resolution, connecting, and TLS trust.
/// {@link #system()} in production; tests substitute fixed DNS answers and a dialer that sends
/// fake public addresses to a local server. None of the clients uses a proxy (an egress proxy
/// would re-resolve the host and defeat the pinning).
public record Network(Resolver resolver, Dialer dialer, SSLContext tls) {
    /// Name resolution: `getaddrinfo` in production (Ruby's `Resolv.getaddresses` reads
    /// /etc/hosts, then DNS).
    @FunctionalInterface
    public interface Resolver {
        List<InetAddress> lookup(String host) throws IOException;
    }

    /// Opens the TCP connection to an address within `timeoutMillis`.
    @FunctionalInterface
    public interface Dialer {
        Socket connect(InetSocketAddress address, int timeoutMillis) throws IOException;
    }

    public static final Resolver SYSTEM_RESOLVER = host -> Arrays.asList(InetAddress.getAllByName(host));

    public static final Dialer TCP_DIALER = (address, timeoutMillis) -> {
        var socket = new Socket(java.net.Proxy.NO_PROXY);
        try {
            socket.setTcpNoDelay(true);
            socket.connect(address, timeoutMillis);
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    };

    private static final class SystemHolder {
        static final Network SYSTEM;

        static {
            try {
                SYSTEM = new Network(SYSTEM_RESOLVER, TCP_DIALER, SSLContext.getDefault());
            } catch (NoSuchAlgorithmException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    /// The system resolver, real connections, and the JDK's default trust store.
    public static Network system() {
        return SystemHolder.SYSTEM;
    }

    public Network withResolver(Resolver resolver) {
        return new Network(resolver, dialer, tls);
    }

    public Network withTls(SSLContext tls) {
        return new Network(resolver, dialer, tls);
    }
}
