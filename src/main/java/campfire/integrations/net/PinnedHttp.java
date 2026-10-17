package campfire.integrations.net;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.PushbackInputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/// One HTTP/1.1 request over a fresh connection, the way `Net::HTTP` makes it with
/// `http.ipaddr = ip`: the connection goes to the pinned (already vetted) address while the
/// `Host` header, TLS SNI and certificate verification use the URL's host. `open` covers the
/// connect and TLS handshake, `read` each read, and a {@link Deadline} the whole exchange
/// (enforced by closing the socket, so a peer that trickles bytes can't hold it open). A body
/// the client asked to be compressed (`Accept-Encoding: gzip;q=1.0,deflate;q=0.6,identity;q=0.3`)
/// is inflated as it's read, and reading stops at the caller's size limit.
///
/// Why not `java.net.http.HttpClient`: pinning is the SSRF (DNS rebinding) protection, and
/// `HttpClient` always resolves the URL's host itself. Requesting the IP literal instead would
/// need the JVM-wide `jdk.httpclient.allowRestrictedHeaders=host` switch for the `Host` header,
/// a client per host for SNI, and hostname verification against the name rather than the IP
/// (only possible by disabling it JVM-wide or with a custom trust manager). It also has no
/// per-read timeout and writes its own header set. So this is a deliberately small HTTP/1.1
/// exchange over `(SSL)Socket`; TLS, decompression and timeouts are the JDK's.
public final class PinnedHttp {
    private PinnedHttp() {}

    /// `Net::HTTP`'s default open and read timeouts.
    public static final Duration NET_HTTP_DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /// The `Accept-Encoding` `Net::HTTP` adds to requests whose responses have bodies, and then
    /// decodes itself.
    public static final String NET_HTTP_ACCEPT_ENCODING = "gzip;q=1.0,deflate;q=0.6,identity;q=0.3";

    private static final int MAX_HEAD_BYTES = 128 * 1024;
    private static final int MAX_HEADERS = 256;

    public record Header(String name, String value) {}

    public record Timeouts(Duration open, Duration read) {
        public static final Timeouts NET_HTTP = new Timeouts(NET_HTTP_DEFAULT_TIMEOUT, NET_HTTP_DEFAULT_TIMEOUT);
    }

    /// Where the request goes: `Net::HTTP.new(host, port)`, `use_ssl` and `http.ipaddr`.
    /// `host` is as `URI#host` gives it (IPv6 literals in brackets).
    public record Target(boolean https, String host, int port, InetAddress pinned) {
        /// `Net::HTTP#addr_port`: the `Host` header when the request doesn't carry one.
        public String hostHeader() {
            String h = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
            return port == (https ? 443 : 80) ? h : h + ":" + port;
        }

        String bareHost() {
            return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        }
    }

    /// The request as `Net::HTTP` writes it: method, `request_uri`, and headers in order.
    public record Request(String method, String target, List<Header> headers, byte[] body, boolean decodeContent) {
        /// `Net::HTTP::Get.new(uri)` / `Head.new(uri)` / `Post.new(path, headers)`: the caller's
        /// headers, then `Accept-Encoding` (decoded when the response has a body), `Accept`,
        /// `User-Agent`, and `Host` when built from a URI (`uriHost`, else null).
        public static Request netHttp(String method, String target, String uriHost, List<Header> headers, byte[] body) {
            var list = new ArrayList<>(headers);
            boolean decode = false;
            if (!has(list, "accept-encoding") && !has(list, "range")) {
                decode = !method.equals("HEAD");
                list.add(new Header("Accept-Encoding", NET_HTTP_ACCEPT_ENCODING));
            }
            defaultHeader(list, "Accept", "*/*");
            defaultHeader(list, "User-Agent", "Ruby");
            if (uriHost != null) defaultHeader(list, "Host", uriHost);
            return new Request(method, target, list, body == null ? new byte[0] : body, decode);
        }

        /// `Net::HTTP#request` on a connection that wasn't started (`Connection: close`), then
        /// `begin_transport` (`Host` from `addr_port` unless the request has one).
        public Request transport(boolean unstarted, Target target) {
            var list = new ArrayList<>(headers);
            if (unstarted) defaultHeader(list, "Connection", "close");
            defaultHeader(list, "Host", target.hostHeader());
            return new Request(method, this.target, list, body, decodeContent);
        }

        private static boolean has(List<Header> headers, String name) {
            for (var h : headers) if (h.name().equalsIgnoreCase(name)) return true;
            return false;
        }

        private static void defaultHeader(List<Header> headers, String name, String value) {
            if (!has(headers, name)) headers.add(new Header(name, value));
        }
    }

    /// A failed exchange, with the `Net::HTTP` exception it stands for.
    public static final class HttpException extends IOException {
        public enum Kind {
            /// `Net::OpenTimeout`
            OPEN_TIMEOUT("Net::OpenTimeout"),
            /// `Net::ReadTimeout`
            READ_TIMEOUT("Net::ReadTimeout"),
            /// `OpenSSL::SSL::SSLError`: the TLS handshake or a TLS read failed.
            TLS("OpenSSL::SSL::SSLError"),
            /// Connection failures (`SystemCallError`, `EOFError`).
            IO("SystemCallError"),
            /// A malformed response (`Net::HTTPBadResponse`).
            PROTOCOL("Net::HTTPBadResponse"),
            /// `Zlib::Error` while inflating a compressed body.
            INFLATE("Zlib::Error");

            public final String rubyClass;

            Kind(String rubyClass) {
                this.rubyClass = rubyClass;
            }
        }

        public final Kind kind;

        public HttpException(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public HttpException(Kind kind, String message) {
            this(kind, message, null);
        }
    }

    // --- The exchange ------------------------------------------------------------------------

    private static final ScheduledThreadPoolExecutor WATCHDOG = watchdog();

    private static ScheduledThreadPoolExecutor watchdog() {
        var executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            var thread = new Thread(runnable, "pinned-http-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /// Connects (within `open`), sends the request, and returns once the response head has
    /// arrived; the body is read with {@link Response#readBody}. Close the response.
    public static Response exchange(Network net, Target target, Request request, Timeouts timeouts, Deadline deadline) throws HttpException {
        validate(request);
        Deadline opening = deadline.min(timeouts.open());
        var fired = new AtomicBoolean();
        Socket socket = null;
        ScheduledFuture<?> watchdog = null;
        boolean opened = false;
        try {
            if (opening.expired()) throw new HttpException(HttpException.Kind.OPEN_TIMEOUT, "execution expired");
            try {
                socket = net.dialer().connect(new InetSocketAddress(target.pinned(), target.port()), opening.timeoutMillis(timeouts.open()));
            } catch (SocketTimeoutException e) {
                throw new HttpException(HttpException.Kind.OPEN_TIMEOUT, "execution expired", e);
            }
            watchdog = arm(socket, opening, fired);
            if (target.https()) socket = startTls(net, target, socket, opening.timeoutMillis(timeouts.open()));
            opened = true;
            if (watchdog != null) watchdog.cancel(false);
            watchdog = arm(socket, deadline, fired);
            socket.setSoTimeout(deadline.timeoutMillis(timeouts.read()));

            OutputStream out = socket.getOutputStream();
            out.write(head(request));
            if (request.body().length > 0) out.write(request.body());
            out.flush();

            var in = new BufferedInputStream(socket.getInputStream(), 16 * 1024);
            var response = readHead(in, request, timeouts, deadline, socket, watchdog, fired);
            return response;
        } catch (HttpException e) {
            closeQuietly(socket, watchdog);
            throw e;
        } catch (IOException e) {
            closeQuietly(socket, watchdog);
            throw translate(e, fired.get(), opened);
        } catch (RuntimeException e) {
            closeQuietly(socket, watchdog);
            throw new HttpException(HttpException.Kind.IO, String.valueOf(e.getMessage()), e);
        }
    }

    /// Closes the socket when `deadline` passes.
    private static ScheduledFuture<?> arm(Socket socket, Deadline deadline, AtomicBoolean fired) {
        if (!deadline.bounded()) return null;
        return WATCHDOG.schedule(() -> {
            fired.set(true);
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing is all that's wanted
            }
        }, deadline.remainingNanos(), TimeUnit.NANOSECONDS);
    }

    private static Socket startTls(Network net, Target target, Socket tcp, int timeoutMillis) throws IOException {
        String host = target.bareHost();
        var tls = (SSLSocket) net.tls().getSocketFactory().createSocket(tcp, host, target.port(), true);
        SSLParameters parameters = tls.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        if (Guard.ipLiteral(host) == null) {
            try {
                parameters.setServerNames(List.of(new SNIHostName(host.endsWith(".") ? host.substring(0, host.length() - 1) : host)));
            } catch (IllegalArgumentException e) {
                // not a name SNI can carry; the certificate is still checked against the host
            }
        }
        parameters.setApplicationProtocols(new String[0]);
        tls.setSSLParameters(parameters);
        tls.setSoTimeout(timeoutMillis);
        tls.startHandshake();
        return tls;
    }

    private static HttpException translate(IOException e, boolean deadlinePassed, boolean opened) {
        if (e instanceof HttpException http) return http;
        if (deadlinePassed || e instanceof SocketTimeoutException) {
            return opened ? new HttpException(HttpException.Kind.READ_TIMEOUT, "Net::ReadTimeout", e)
                : new HttpException(HttpException.Kind.OPEN_TIMEOUT, "execution expired", e);
        }
        if (e instanceof SSLException) return new HttpException(HttpException.Kind.TLS, "SSL error: " + e.getMessage(), e);
        if (e instanceof ZipException) return new HttpException(HttpException.Kind.INFLATE, String.valueOf(e.getMessage()), e);
        return new HttpException(HttpException.Kind.IO, String.valueOf(e.getMessage()), e);
    }

    private static void closeQuietly(Socket socket, ScheduledFuture<?> watchdog) {
        if (watchdog != null) watchdog.cancel(false);
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    private static void validate(Request request) throws HttpException {
        if (!request.method().chars().allMatch(c -> c >= 'A' && c <= 'Z')) throw new HttpException(HttpException.Kind.PROTOCOL, "bad method");
        if (!clean(request.target()) || request.target().indexOf(' ') >= 0) throw new HttpException(HttpException.Kind.PROTOCOL, "bad request target");
        for (var h : request.headers()) {
            if (h.name().isEmpty() || !clean(h.name()) || h.name().indexOf(':') >= 0 || !clean(h.value())) {
                throw new HttpException(HttpException.Kind.PROTOCOL, "bad header " + h.name());
            }
        }
    }

    private static boolean clean(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n' || c == 0 || c > 0xff) return false;
        }
        return true;
    }

    private static byte[] head(Request request) {
        var head = new StringBuilder(256);
        head.append(request.method()).append(' ').append(request.target()).append(" HTTP/1.1\r\n");
        boolean hasLength = false;
        for (var h : request.headers()) {
            head.append(h.name()).append(": ").append(h.value()).append("\r\n");
            hasLength |= h.name().equalsIgnoreCase("content-length");
        }
        if (!hasLength && request.body().length > 0) head.append("Content-Length: ").append(request.body().length).append("\r\n");
        head.append("\r\n");
        return head.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static Response readHead(InputStream in, Request request, Timeouts timeouts, Deadline deadline, Socket socket,
        ScheduledFuture<?> watchdog, AtomicBoolean fired) throws IOException {
        var budget = new int[] {MAX_HEAD_BYTES};
        while (true) {
            String statusLine = readLine(in, budget);
            if (statusLine == null) throw new HttpException(HttpException.Kind.IO, "end of file reached");
            if (statusLine.isEmpty()) continue;
            if (!statusLine.startsWith("HTTP/1.") || statusLine.length() < 12 || statusLine.charAt(8) != ' ') {
                throw new HttpException(HttpException.Kind.PROTOCOL, "wrong status line: " + statusLine);
            }
            int status;
            try {
                status = Integer.parseInt(statusLine.substring(9, 12));
            } catch (NumberFormatException e) {
                throw new HttpException(HttpException.Kind.PROTOCOL, "wrong status line: " + statusLine);
            }
            String reason = statusLine.length() > 13 ? statusLine.substring(13) : "";
            var headers = new ArrayList<Header>();
            while (true) {
                String line = readLine(in, budget);
                if (line == null) throw new HttpException(HttpException.Kind.IO, "end of file reached");
                if (line.isEmpty()) break;
                if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && !headers.isEmpty()) {
                    var last = headers.removeLast();
                    headers.add(new Header(last.name(), last.value() + " " + line.strip()));
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon <= 0) throw new HttpException(HttpException.Kind.PROTOCOL, "wrong header line format");
                if (headers.size() >= MAX_HEADERS) throw new HttpException(HttpException.Kind.PROTOCOL, "too many headers");
                headers.add(new Header(line.substring(0, colon).strip(), line.substring(colon + 1).strip()));
            }
            if (status >= 100 && status < 200 && status != 101) continue;
            return new Response(status, reason, headers, in, request, timeouts, deadline, socket, watchdog, fired);
        }
    }

    /// A header line (CRLF or LF terminated) as Latin-1, or null at EOF before any byte.
    private static String readLine(InputStream in, int[] budget) throws IOException {
        var line = new StringBuilder(64);
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (line.isEmpty()) return null;
                throw new HttpException(HttpException.Kind.IO, "end of file reached");
            }
            if (--budget[0] < 0) throw new HttpException(HttpException.Kind.PROTOCOL, "response head too large");
            if (b == '\n') {
                int end = line.length();
                if (end > 0 && line.charAt(end - 1) == '\r') line.setLength(end - 1);
                return line.toString();
            }
            line.append((char) b);
        }
    }

    // --- The response ------------------------------------------------------------------------

    /// A response whose head has arrived. Close it to release the connection.
    public static final class Response implements AutoCloseable {
        public final int status;
        /// The status line's reason phrase (`response.message`).
        public final String reason;
        public final List<Header> headers;
        private final InputStream in;
        private final Request request;
        private final Socket socket;
        private final ScheduledFuture<?> watchdog;
        private final AtomicBoolean fired;
        private boolean consumed;

        Response(int status, String reason, List<Header> headers, InputStream in, Request request, Timeouts timeouts, Deadline deadline,
            Socket socket, ScheduledFuture<?> watchdog, AtomicBoolean fired) {
            this.status = status;
            this.reason = reason;
            this.headers = List.copyOf(headers);
            this.in = in;
            this.request = request;
            this.socket = socket;
            this.watchdog = watchdog;
            this.fired = fired;
        }

        /// `response[name]`: every value of the header joined with ", ", or null.
        public String header(String name) {
            String joined = null;
            for (var h : headers) {
                if (h.name().equalsIgnoreCase(name)) joined = joined == null ? h.value() : joined + ", " + h.value();
            }
            return joined;
        }

        /// `Net::HTTPHeader#content_type`: the media type before any `;`, main and sub type each
        /// stripped (not downcased), or just the main type when there's no `/`.
        public String contentType() {
            String header = header("content-type");
            if (header == null) return null;
            int semicolon = header.indexOf(';');
            String media = semicolon >= 0 ? header.substring(0, semicolon) : header;
            int slash = media.indexOf('/');
            if (slash < 0) return rubyStrip(media);
            String sub = media.substring(slash + 1);
            int next = sub.indexOf('/');
            if (next >= 0) sub = sub.substring(0, next);
            return rubyStrip(media.substring(0, slash)) + "/" + rubyStrip(sub);
        }

        /// `Net::HTTPHeader#content_length`: the first run of digits, null when absent; a header
        /// without digits is `Net::HTTPHeaderSyntaxError`.
        public Long contentLength() throws HttpException {
            String header = header("content-length");
            if (header == null) return null;
            int i = 0;
            while (i < header.length() && (header.charAt(i) < '0' || header.charAt(i) > '9')) i++;
            int start = i;
            while (i < header.length() && header.charAt(i) >= '0' && header.charAt(i) <= '9') i++;
            if (i == start) throw new HttpException(HttpException.Kind.PROTOCOL, "wrong Content-Length format");
            try {
                return Long.parseLong(header.substring(start, i));
            } catch (NumberFormatException e) {
                return Long.MAX_VALUE;
            }
        }

        /// Reads the body (inflating it when `Net::HTTP` would), or null once it would exceed
        /// `limit` bytes; reading stops there. `Net::HTTP` reads any size.
        public byte[] readBody(long limit) throws HttpException {
            if (consumed) throw new IllegalStateException("body already read");
            consumed = true;
            try (InputStream body = body()) {
                var out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                long total = 0;
                int n;
                while ((n = body.read(buffer)) > 0) {
                    total += n;
                    if (total > limit) return null;
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            } catch (IOException e) {
                throw translate(e, fired.get(), true);
            }
        }

        private InputStream body() throws IOException {
            if (request.method().equals("HEAD") || status == 204 || status == 304 || (status >= 100 && status < 200)) {
                return InputStream.nullInputStream();
            }
            InputStream raw;
            String transferEncoding = header("transfer-encoding");
            if (transferEncoding != null) {
                String[] codings = transferEncoding.split(",");
                raw = codings[codings.length - 1].strip().equalsIgnoreCase("chunked") ? new Chunked(in) : in;
            } else {
                String length = header("content-length");
                if (length != null) {
                    long declared = framingLength();
                    raw = new Bounded(in, declared);
                } else {
                    raw = in;
                }
            }
            if (!inflates()) return raw;
            return inflate(raw);
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

        /// `Content-Length` for framing: one value (repeated values must agree), digits only.
        private long framingLength() throws HttpException {
            Long length = null;
            for (var h : headers) {
                if (!h.name().equalsIgnoreCase("content-length")) continue;
                for (String part : h.value().split(",")) {
                    String digits = part.strip();
                    if (digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9') || digits.length() > 18) {
                        throw new HttpException(HttpException.Kind.PROTOCOL, "invalid Content-Length");
                    }
                    long value = Long.parseLong(digits);
                    if (length != null && length != value) throw new HttpException(HttpException.Kind.PROTOCOL, "conflicting Content-Length");
                    length = value;
                }
            }
            return length == null ? 0 : length;
        }

        /// `Net::HTTPResponse#inflater`: gzip, x-gzip or deflate, unless it's a range.
        private boolean inflates() {
            if (!request.decodeContent() || header("content-range") != null) return false;
            String encoding = header("content-encoding");
            if (encoding == null) return false;
            return switch (encoding.toLowerCase(Locale.ROOT)) {
                case "gzip", "x-gzip", "deflate" -> true;
                default -> false;
            };
        }

        @Override
        public void close() {
            closeQuietly(socket, watchdog);
        }
    }

    /// Ruby's `String#strip`: ASCII whitespace and NUL.
    static String rubyStrip(String s) {
        int start = 0, end = s.length();
        while (start < end && isRubySpace(s.charAt(start))) start++;
        while (end > start && (isRubySpace(s.charAt(end - 1)) || s.charAt(end - 1) == 0)) end--;
        return s.substring(start, end);
    }

    private static boolean isRubySpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0b || c == '\f' || c == '\r';
    }

    /// A body of `Content-Length` bytes; ending early is an error.
    private static final class Bounded extends InputStream {
        private final InputStream in;
        private long remaining;

        Bounded(InputStream in, long length) {
            this.in = in;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining == 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n < 0) throw new EOFException("end of file reached");
            remaining -= n;
            return n;
        }
    }

    /// `Transfer-Encoding: chunked`
    private static final class Chunked extends InputStream {
        private final InputStream in;
        private long remaining;
        private boolean done;

        Chunked(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (done) return -1;
            if (remaining == 0) {
                remaining = nextSize();
                if (remaining == 0) {
                    // trailers
                    var budget = new int[] {MAX_HEAD_BYTES};
                    String line;
                    do {
                        line = readLine(in, budget);
                    } while (line != null && !line.isEmpty());
                    done = true;
                    return -1;
                }
            }
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n < 0) throw new EOFException("end of file reached");
            remaining -= n;
            if (remaining == 0) {
                var budget = new int[] {2};
                String crlf = readLine(in, budget);
                if (crlf == null || !crlf.isEmpty()) throw new HttpException(HttpException.Kind.PROTOCOL, "wrong chunk terminator");
            }
            return n;
        }

        private long nextSize() throws IOException {
            var budget = new int[] {4096};
            String line = readLine(in, budget);
            if (line == null) throw new EOFException("end of file reached");
            int semicolon = line.indexOf(';');
            String hex = (semicolon >= 0 ? line.substring(0, semicolon) : line).strip();
            if (hex.isEmpty() || hex.length() > 15 || !hex.chars().allMatch(c -> Character.digit(c, 16) >= 0 && c < 0x80)) {
                throw new HttpException(HttpException.Kind.PROTOCOL, "wrong chunk size line: " + line);
            }
            return Long.parseLong(hex, 16);
        }
    }
}
