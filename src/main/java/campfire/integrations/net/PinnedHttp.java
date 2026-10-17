package campfire.integrations.net;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.codec.DecoderException;
import io.vertx.core.Context;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;
import javax.net.ssl.SSLException;

/// One HTTP/1.1 request to a pinned (already vetted) address, the way `Net::HTTP` makes it with
/// `http.ipaddr = ip`: Vert.x connects to the address (`RequestOptions.setServer`) while the
/// `Host` header, SNI and certificate verification use the URL's host. Pinning is the DNS
/// rebinding protection, so nothing here resolves a name.
///
/// Callers block (on virtual threads); the event loop never does. Every wait is bounded by its
/// phase's timeout (`open` for connecting and the TLS handshake, `read` for the response head and
/// each body chunk) and by what's left of `total`, so a peer that trickles bytes can't hold an
/// exchange open. A body the client asked to be compressed is inflated as it's read, and reading
/// stops at the caller's size limit, so neither the wire nor the inflater can exceed it.
public final class PinnedHttp {
    private PinnedHttp() {}

    /// The `Accept-Encoding` `Net::HTTP` adds to requests whose responses have bodies, and then
    /// decodes itself.
    public static final String NET_HTTP_ACCEPT_ENCODING = "gzip;q=1.0,deflate;q=0.6,identity;q=0.3";

    public record Header(String name, String value) {}

    public record Timeouts(Duration open, Duration read, Duration total) {}

    /// Where the request goes. `host` is as the URL has it (IPv6 literals in brackets).
    public record Target(boolean https, String host, int port, InetAddress pinned) {
        /// The host, plus the port unless it's the scheme's default.
        String hostHeader() {
            String h = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
            return port == (https ? 443 : 80) ? h : h + ":" + port;
        }

        String bareHost() {
            return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        }
    }

    /// `Accept-Encoding`, `Accept`, `User-Agent` and `Host` are added as `Net::HTTP` adds them
    /// unless `headers` has them; Vert.x adds `Content-Length` and `Connection: close`.
    public record Request(String method, String uri, List<Header> headers, byte[] body) {
        public Request(String method, String uri) {
            this(method, uri, List.of(), new byte[0]);
        }
    }

    /// A failed exchange.
    public static final class HttpException extends IOException {
        public enum Kind {
            OPEN_TIMEOUT,
            READ_TIMEOUT,
            /// The TLS handshake failed (an untrusted certificate, or one for another name).
            TLS,
            /// Connection failures.
            IO,
            /// A malformed request or response.
            PROTOCOL,
            /// A compressed body that doesn't inflate.
            INFLATE
        }

        public final Kind kind;

        HttpException(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }
    }

    /// Connects, sends the request, and returns once the response head has arrived; the body is
    /// read with {@link Response#readBody}. Close the response.
    public static Response exchange(Network net, Target target, Request request, Timeouts timeouts) throws HttpException {
        validate(request);
        long deadline = System.nanoTime() + timeouts.total().toNanos();
        MultiMap headers = HttpHeaders.headers();
        for (var h : request.headers()) headers.add(h.name(), h.value());
        boolean decode = false;
        if (!headers.contains(HttpHeaders.ACCEPT_ENCODING) && !headers.contains("range")) {
            decode = !request.method().equals("HEAD");
            headers.add(HttpHeaders.ACCEPT_ENCODING, NET_HTTP_ACCEPT_ENCODING);
        }
        if (!headers.contains(HttpHeaders.ACCEPT)) headers.add(HttpHeaders.ACCEPT, "*/*");
        if (!headers.contains(HttpHeaders.USER_AGENT)) headers.add(HttpHeaders.USER_AGENT, "Ruby");
        if (!headers.contains(HttpHeaders.HOST)) headers.add(HttpHeaders.HOST, target.hostHeader());

        var server = net.route().apply(new InetSocketAddress(target.pinned(), target.port()));
        var options = new RequestOptions()
            .setMethod(HttpMethod.valueOf(request.method()))
            .setServer(SocketAddress.inetSocketAddress(server))
            .setHost(target.bareHost())
            .setPort(target.port())
            .setSsl(target.https())
            .setURI(request.uri())
            .setHeaders(headers)
            .setFollowRedirects(false)
            .setConnectTimeout(timeouts.open().toMillis());
        var exchange = new Exchange(net, options, request.body());
        exchange.await(exchange.opened, timeouts.open(), deadline, HttpException.Kind.OPEN_TIMEOUT);
        HttpClientResponse response = exchange.await(exchange.head, timeouts.read(), deadline, HttpException.Kind.READ_TIMEOUT);
        return new Response(exchange, response, decode, timeouts.read(), deadline);
    }

    /// Rejects what would split the request (CR, LF, NUL) or doesn't fit in it: Vert.x writes the
    /// request line and headers unchecked.
    private static void validate(Request request) throws HttpException {
        if (!request.method().chars().allMatch(c -> c >= 'A' && c <= 'Z')) throw protocol("bad method");
        if (!clean(request.uri()) || request.uri().indexOf(' ') >= 0) throw protocol("bad request target");
        for (var h : request.headers()) {
            if (h.name().isEmpty() || !clean(h.name()) || h.name().indexOf(':') >= 0 || !clean(h.value())) throw protocol("bad header " + h.name());
        }
    }

    private static boolean clean(String s) {
        return s.chars().noneMatch(c -> c == '\r' || c == '\n' || c == 0 || c > 0xff);
    }

    private static HttpException protocol(String message) {
        return new HttpException(HttpException.Kind.PROTOCOL, message, null);
    }

    /// `timeout` is the kind for Vert.x's own timeouts (its wait for a connection).
    private static HttpException translate(Throwable e, HttpException.Kind timeout) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpException http) return http;
            if (t instanceof SSLException) return new HttpException(HttpException.Kind.TLS, "SSL error: " + t.getMessage(), e);
            if (t instanceof ZipException) return new HttpException(HttpException.Kind.INFLATE, String.valueOf(t.getMessage()), e);
            if (t instanceof ConnectTimeoutException) return new HttpException(HttpException.Kind.OPEN_TIMEOUT, String.valueOf(t.getMessage()), e);
            if (t instanceof TimeoutException) return new HttpException(timeout, String.valueOf(t.getMessage()), e);
            if (t instanceof DecoderException || t instanceof IllegalArgumentException) {
                return new HttpException(HttpException.Kind.PROTOCOL, String.valueOf(t.getMessage()), e);
            }
        }
        return new HttpException(HttpException.Kind.IO, String.valueOf(e.getMessage()), e);
    }

    private static long remainingNanos(Duration phase, long deadline) {
        return Math.max(0, Math.min(phase.toNanos(), deadline - System.nanoTime()));
    }

    private static HttpException interrupted(InterruptedException e) {
        Thread.currentThread().interrupt();
        return new HttpException(HttpException.Kind.IO, "interrupted", e);
    }

    /// The event-loop side of one exchange. Vert.x calls back on `context`; the caller only waits
    /// on the futures and the body queue, and asks for each chunk as it needs it.
    private static final class Exchange {
        private static final Object END = new Object();

        final Context context;
        final CompletableFuture<HttpClientRequest> opened = new CompletableFuture<>();
        final CompletableFuture<HttpClientResponse> head = new CompletableFuture<>();
        /// Body chunks ({@link Buffer}), then {@link #END} or the failure.
        final BlockingQueue<Object> body = new LinkedBlockingQueue<>();

        Exchange(Network net, RequestOptions options, byte[] payload) {
            context = net.vertx().getOrCreateContext();
            context.runOnContext(v -> {
                try {
                    net.http().request(options).onComplete(opening -> {
                        if (opening.failed()) {
                            opened.completeExceptionally(opening.cause());
                            return;
                        }
                        HttpClientRequest request = opening.result();
                        // The caller gave up while it was connecting.
                        if (!opened.complete(request)) {
                            request.reset();
                            return;
                        }
                        // The handlers are set in this callback, on the event loop, so no chunk
                        // can arrive before them.
                        (payload.length == 0 ? request.send() : request.send(Buffer.buffer(payload))).onComplete(sent -> {
                            if (sent.failed()) {
                                head.completeExceptionally(sent.cause());
                                return;
                            }
                            HttpClientResponse response = sent.result();
                            response.pause();
                            response.handler(body::add).endHandler(end -> body.add(END)).exceptionHandler(body::add);
                            head.complete(response);
                        });
                    });
                } catch (RuntimeException e) {
                    opened.completeExceptionally(e);
                }
            });
        }

        <T> T await(CompletableFuture<T> future, Duration phase, long deadline, HttpException.Kind timeout) throws HttpException {
            try {
                return future.get(remainingNanos(phase, deadline), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                close();
                throw new HttpException(timeout, timeout == HttpException.Kind.OPEN_TIMEOUT ? "execution expired" : "Net::ReadTimeout", e);
            } catch (ExecutionException e) {
                close();
                throw translate(e.getCause(), timeout);
            } catch (InterruptedException e) {
                close();
                throw interrupted(e);
            }
        }

        /// The next chunk or {@link #END}, once the response is asked for it.
        Object next(HttpClientResponse response, Duration read, long deadline) throws HttpException {
            context.runOnContext(v -> response.fetch(1));
            try {
                Object next = body.poll(remainingNanos(read, deadline), TimeUnit.NANOSECONDS);
                if (next == null) throw new HttpException(HttpException.Kind.READ_TIMEOUT, "Net::ReadTimeout", null);
                if (next instanceof Throwable t) throw translate(t, HttpException.Kind.READ_TIMEOUT);
                return next;
            } catch (InterruptedException e) {
                throw interrupted(e);
            }
        }

        /// Drops the connection. A request still connecting is reset as soon as it opens.
        void close() {
            if (opened.cancel(false) || opened.isCompletedExceptionally()) return;
            HttpClientRequest request = opened.join();
            context.runOnContext(v -> request.reset());
        }
    }

    // --- The response ------------------------------------------------------------------------

    /// A response whose head has arrived. Close it to release the connection.
    public static final class Response implements AutoCloseable {
        public final int status;
        /// The status line's reason phrase.
        public final String reason;
        private final MultiMap headers;
        private final Exchange exchange;
        private final HttpClientResponse response;
        private final boolean decode;
        private final Duration read;
        private final long deadline;
        private boolean consumed;
        private boolean ended;

        Response(Exchange exchange, HttpClientResponse response, boolean decode, Duration read, long deadline) {
            this.status = response.statusCode();
            this.reason = response.statusMessage();
            this.headers = response.headers();
            this.exchange = exchange;
            this.response = response;
            this.decode = decode;
            this.read = read;
            this.deadline = deadline;
        }

        /// Every value of the header joined with ", ", or null.
        public String header(String name) {
            List<String> values = headers.getAll(name);
            return values.isEmpty() ? null : String.join(", ", values);
        }

        /// `Net::HTTPHeader#content_type`: the media type before any `;`, main and sub type each
        /// stripped (not downcased), or just the main type when there's no `/`.
        public String contentType() {
            String header = header("content-type");
            if (header == null) return null;
            int semicolon = header.indexOf(';');
            String media = semicolon >= 0 ? header.substring(0, semicolon) : header;
            int slash = media.indexOf('/');
            if (slash < 0) return media.strip();
            String sub = media.substring(slash + 1);
            int next = sub.indexOf('/');
            if (next >= 0) sub = sub.substring(0, next);
            return media.substring(0, slash).strip() + "/" + sub.strip();
        }

        /// The declared length, or null. Netty has already rejected one that doesn't frame the body.
        public Long contentLength() {
            String header = headers.get(HttpHeaders.CONTENT_LENGTH);
            if (header == null) return null;
            try {
                return Long.parseLong(header.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /// Reads the body (inflating it when the client asked for compression), or null once it
        /// would exceed `limit` bytes; reading stops there.
        public byte[] readBody(long limit) throws HttpException {
            if (consumed) throw new IllegalStateException("body already read");
            consumed = true;
            InputStream raw = new Body();
            try (InputStream body = inflates() ? inflate(raw) : raw) {
                byte[] bytes = body.readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE - 8));
                return bytes.length > limit ? null : bytes;
            } catch (IOException e) {
                throw translate(e, HttpException.Kind.READ_TIMEOUT);
            }
        }

        /// `Net::HTTPResponse#inflater`: gzip, x-gzip or deflate, unless it's a range.
        private boolean inflates() {
            if (!decode || header("content-range") != null) return false;
            String encoding = header("content-encoding");
            if (encoding == null) return false;
            return switch (encoding.toLowerCase(Locale.ROOT)) {
                case "gzip", "x-gzip", "deflate" -> true;
                default -> false;
            };
        }

        /// `Zlib::Inflate.new(32 + Zlib::MAX_WBITS)`: gzip (JDK 25's `GZIPInputStream` reads
        /// concatenated members) or zlib, detected from the first two bytes; an empty body stays
        /// empty. Output is produced only as it's read, so {@link #readBody}'s limit bounds it.
        private static InputStream inflate(InputStream raw) throws IOException {
            var in = new PushbackInputStream(raw, 2);
            int first = in.read();
            if (first < 0) return InputStream.nullInputStream();
            int second = in.read();
            if (second >= 0) in.unread(second);
            in.unread(first);
            return first == 0x1f && second == 0x8b ? new GZIPInputStream(in, 16 * 1024) : new InflaterInputStream(in, new Inflater(), 16 * 1024);
        }

        @Override
        public void close() {
            if (!ended) exchange.close();
        }

        /// The raw body, a chunk at a time as it's read.
        private final class Body extends InputStream {
            private Buffer chunk;
            private int position;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                while (chunk == null || position == chunk.length()) {
                    if (ended) return -1;
                    Object next = exchange.next(response, read, deadline);
                    if (next == Exchange.END) {
                        ended = true;
                        return -1;
                    }
                    chunk = (Buffer) next;
                    position = 0;
                }
                int n = Math.min(len, chunk.length() - position);
                chunk.getBytes(position, position + n, b, off);
                position += n;
                return n;
            }
        }
    }
}
