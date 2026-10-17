package campfire.integrations;

import campfire.integrations.net.Guard;
import campfire.integrations.net.Network;
import campfire.rails.Json;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/// Fakes for the integration tests (after the Rust port's test_support.rs): DNS answers, a
/// dialer that sends fake public addresses to a local server, and that server (plain or TLS),
/// which replays canned responses and records what it was asked.
public final class TestSupport {
    private TestSupport() {}

    public static final Path TESTDATA = Path.of("src/test/java/campfire/integrations/testdata");

    public static Object json(String name) {
        try {
            return Json.parse(Files.readString(TESTDATA.resolve(name), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /// Fixed answers per host. A host with several answer lists gives the next one on each
    /// lookup and then keeps giving the last.
    public static final class FakeResolver implements Network.Resolver {
        private final Map<String, List<List<InetAddress>>> answers = new HashMap<>();
        public final List<String> lookups = Collections.synchronizedList(new ArrayList<>());

        public FakeResolver set(String host, List<List<InetAddress>> list) {
            synchronized (answers) {
                answers.put(host, new ArrayList<>(list));
            }
            return this;
        }

        public FakeResolver set(String host, String... ips) {
            return set(host, List.of(ips(ips)));
        }

        @Override
        public List<InetAddress> lookup(String host) throws IOException {
            lookups.add(host);
            synchronized (answers) {
                var list = answers.get(host);
                if (list == null) throw new IOException("no address for " + host);
                return list.size() > 1 ? list.removeFirst() : list.getFirst();
            }
        }
    }

    public static List<InetAddress> ips(String... ips) {
        var out = new ArrayList<InetAddress>();
        for (String ip : ips) out.add(Guard.parseAddress(ip));
        return out;
    }

    /// Connects the given addresses (any port) to `to`; anything else is dialed for real.
    public static final class MappingDialer implements Network.Dialer {
        private final Set<InetAddress> publicIps;
        private final int toPort;
        public final List<InetSocketAddress> dialed = new CopyOnWriteArrayList<>();

        public MappingDialer(Set<InetAddress> publicIps, int toPort) {
            this.publicIps = publicIps;
            this.toPort = toPort;
        }

        @Override
        public Socket connect(InetSocketAddress address, int timeoutMillis) throws IOException {
            dialed.add(address);
            var target = publicIps.contains(address.getAddress()) ? new InetSocketAddress(InetAddress.getLoopbackAddress(), toPort) : address;
            return Network.TCP_DIALER.connect(target, timeoutMillis);
        }
    }

    // --- TLS --------------------------------------------------------------------------------

    private static byte[] pem(String name) throws IOException {
        String text = Files.readString(TESTDATA.resolve("tls").resolve(name));
        String body = text.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static Certificate certificate(String name) throws Exception {
        return CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(pem(name)));
    }

    /// A client context that trusts only the test CA.
    public static SSLContext clientTls() {
        try {
            var store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setCertificateEntry("ca", certificate("ca.pem"));
            var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            var context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            return context;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /// A client context that trusts nothing.
    public static SSLContext untrustingTls() {
        try {
            var store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            var context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            return context;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /// The server's context: a certificate for fcm.googleapis.com, www.example.com and
    /// example.com, signed by the test CA.
    static SSLContext serverTls() {
        try {
            var key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pem("server.key")));
            var store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("server", key, new char[0], new Certificate[] {certificate("server.pem"), certificate("ca.pem")});
            var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, new char[0]);
            var context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            return context;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Network network(FakeResolver resolver, MappingDialer dialer) {
        return new Network(resolver, dialer, clientTls());
    }

    // --- The server ---------------------------------------------------------------------------

    public static final class Route {
        public String method, host, path;
        public int status;
        public String reason = "Status";
        public List<String[]> headers = new ArrayList<>();
        public byte[] body = new byte[0];
        public boolean chunked, gzip;

        public Route(String method, String host, String path, int status) {
            this.method = method;
            this.host = host;
            this.path = path;
            this.status = status;
        }

        public Route header(String name, String value) {
            headers.add(new String[] {name, value});
            return this;
        }

        public Route body(String body) {
            return body(body.getBytes(StandardCharsets.UTF_8));
        }

        public Route body(byte[] body) {
            this.body = body;
            return this;
        }
    }

    /// A request as the server saw it.
    public record Received(String method, String target, List<String[]> headers, byte[] body) {
        public String header(String name) {
            for (String[] h : headers) if (h[0].equalsIgnoreCase(name)) return h[1];
            return null;
        }
    }

    public static final class FakeServer implements AutoCloseable {
        private final ServerSocket listener;
        private final List<Route> routes;
        private final SSLContext tls;
        public final List<Received> received = new CopyOnWriteArrayList<>();

        private FakeServer(List<Route> routes, SSLContext tls) throws IOException {
            this.listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            this.routes = routes;
            this.tls = tls;
            Thread.ofVirtual().start(this::acceptLoop);
        }

        public static FakeServer start(List<Route> routes) throws IOException {
            return new FakeServer(routes, null);
        }

        public static FakeServer startTls(List<Route> routes) throws IOException {
            return new FakeServer(routes, serverTls());
        }

        public int port() {
            return listener.getLocalPort();
        }

        private void acceptLoop() {
            while (!listener.isClosed()) {
                try {
                    Socket socket = listener.accept();
                    Thread.ofVirtual().start(() -> {
                        try (Socket s = wrap(socket)) {
                            serve(s);
                        } catch (IOException ignored) {
                            // the client hung up
                        }
                    });
                } catch (IOException e) {
                    return;
                }
            }
        }

        private Socket wrap(Socket socket) throws IOException {
            if (tls == null) return socket;
            var ssl = (SSLSocket) tls.getSocketFactory().createSocket(socket, null, socket.getPort(), true);
            ssl.setUseClientMode(false);
            ssl.startHandshake();
            return ssl;
        }

        private void serve(Socket socket) throws IOException {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            String requestLine = line(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            String method = parts[0], target = parts.length > 1 ? parts[1] : "";
            var headers = new ArrayList<String[]>();
            String headerLine;
            while ((headerLine = line(in)) != null && !headerLine.isEmpty()) {
                int colon = headerLine.indexOf(": ");
                if (colon > 0) headers.add(new String[] {headerLine.substring(0, colon), headerLine.substring(colon + 2)});
            }
            int length = 0;
            String host = "";
            for (String[] h : headers) {
                if (h[0].equalsIgnoreCase("content-length")) length = Integer.parseInt(h[1]);
                if (h[0].equalsIgnoreCase("host")) host = h[1];
            }
            byte[] body = in.readNBytes(length);
            int colon = host.lastIndexOf(':');
            if (colon >= 0 && host.substring(colon + 1).chars().allMatch(Character::isDigit) && !host.substring(colon + 1).isEmpty()) {
                host = host.substring(0, colon);
            }
            received.add(new Received(method, target, headers, body));

            Route route = null;
            for (Route r : routes) {
                if (r.method.equals(method) && (r.host.equals(host) || r.host.equals("*")) && r.path.equals(target)) {
                    route = r;
                    break;
                }
            }
            if (route == null) route = new Route(method, host, target, 404).header("Content-Type", "text/plain").body("not found");
            byte[] content = route.body;
            if (route.gzip) {
                var buffer = new ByteArrayOutputStream();
                try (var gzip = new GZIPOutputStream(buffer)) {
                    gzip.write(content);
                }
                content = buffer.toByteArray();
            }
            var head = new StringBuilder("HTTP/1.1 " + route.status + " " + route.reason + "\r\n");
            boolean hasLength = false;
            for (String[] h : route.headers) {
                head.append(h[0]).append(": ").append(h[1]).append("\r\n");
                hasLength |= h[0].equalsIgnoreCase("content-length");
            }
            if (route.gzip) head.append("Content-Encoding: gzip\r\n");
            if (route.chunked) head.append("Transfer-Encoding: chunked\r\n");
            else if (!hasLength) head.append("Content-Length: ").append(content.length).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            if (!method.equals("HEAD")) {
                if (route.chunked) {
                    for (int i = 0; i < content.length; i += 64 * 1024) {
                        int n = Math.min(64 * 1024, content.length - i);
                        out.write((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.write(content, i, n);
                        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    }
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                } else {
                    out.write(content);
                }
            }
            out.flush();
            if (!(socket instanceof SSLSocket)) socket.shutdownOutput();
        }

        private static String line(InputStream in) throws IOException {
            var out = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) >= 0) {
                if (b == '\n') break;
                out.write(b);
            }
            if (b < 0 && out.size() == 0) return null;
            String s = out.toString(StandardCharsets.ISO_8859_1);
            return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
        }

        @Override
        public void close() throws IOException {
            listener.close();
        }
    }

    /// A server that answers every request with `head` and then a byte of body every 50 ms,
    /// until the client hangs up.
    public static ServerSocket tricklingServer(String head) throws IOException {
        var listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!listener.isClosed()) {
                try {
                    Socket socket = listener.accept();
                    Thread.ofVirtual().start(() -> {
                        try (socket) {
                            socket.getInputStream().read(new byte[4096]);
                            var out = socket.getOutputStream();
                            out.write(head.getBytes(StandardCharsets.US_ASCII));
                            while (true) {
                                out.write(' ');
                                out.flush();
                                Thread.sleep(50);
                            }
                        } catch (IOException | InterruptedException ignored) {
                            // the client hung up
                        }
                    });
                } catch (IOException e) {
                    return;
                }
            }
        });
        return listener;
    }

    /// A gzip bomb: `megabytes` gzip members of a megabyte of zeros each, about 1 KB apiece.
    public static byte[] gzipBomb(int megabytes) {
        try {
            var buffer = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(buffer) {
                {
                    def.setLevel(9);
                }
            }) {
                gzip.write(new byte[1024 * 1024]);
            }
            byte[] member = buffer.toByteArray();
            var out = new ByteArrayOutputStream(member.length * megabytes);
            for (int i = 0; i < megabytes; i++) out.write(member);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
