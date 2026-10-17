package campfire.integrations.net;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/// `RestrictedHTTP::PrivateNetworkGuard` (reference/lib/restricted_http/private_network_guard.rb)
/// and the parts of the surfguard gem it calls (`Surfguard.resolve_public_ips` and
/// `Surfguard.blocked_address?`, default policy), as the Rust port has them.
///
/// A host goes in and a public address to pin comes out. Numeric hosts (including the legacy
/// `inet_aton` forms such as `0x7f.1` or `2130706433`) never reach DNS; names must be plain LDH
/// labels. Every answer is classified and the blocked ones dropped; IPv4 answers come before
/// IPv6 ones, in resolver order within each family.
public final class Guard {
    private Guard() {}

    /// What the guard raises.
    public abstract static sealed class GuardException extends Exception permits Violation, Unresolvable {
        GuardException(String message) {
            super(message, null, false, false);
        }
    }

    /// `RestrictedHTTP::Violation`: the host only resolves to blocked addresses (or is malformed).
    public static final class Violation extends GuardException {
        public Violation(String host) {
            super("Attempt to access private IP via " + host);
        }
    }

    /// `Surfguard::Unresolvable`: the lookup failed or came back empty.
    public static final class Unresolvable extends GuardException {
        public Unresolvable() {
            super("Host could not be resolved");
        }
    }

    /// `PrivateNetworkGuard.resolve(hostname)` with the system resolver.
    public static InetAddress resolvePublic(String host) throws GuardException {
        return resolve(Network.system().resolver(), host);
    }

    /// `PrivateNetworkGuard.resolve(hostname)`: the first public address.
    public static InetAddress resolve(Network.Resolver resolver, String host) throws GuardException {
        var addresses = resolvePublicIps(resolver, host);
        if (addresses.isEmpty()) throw new Violation(host);
        return addresses.getFirst();
    }

    /// `PrivateNetworkGuard.private_ip?(ip)`
    public static boolean isPrivate(InetAddress ip) {
        return blocked(ip);
    }

    /// `Surfguard.resolve_public_ips(host)`. Malformed input comes back empty rather than raising.
    public static List<InetAddress> resolvePublicIps(Network.Resolver resolver, String host) throws Unresolvable {
        if (host == null || !normalHost(host)) return List.of();
        List<InetAddress> raw;
        if (!validHostSyntax(host) || malformedNumericHostCandidate(host)) return List.of();
        byte[] literal = getaddrinfoNumeric(host);
        if (literal == null) literal = ipLiteral(host);
        if (literal != null) {
            raw = List.of(toInet(literal));
        } else if (numericHostCandidate(host)) {
            return List.of();
        } else {
            try {
                raw = resolver.lookup(host);
            } catch (IOException | RuntimeException e) {
                throw new Unresolvable();
            }
        }
        if (raw == null || raw.size() > MAX_ADDRESSES) throw new Unresolvable();
        var answers = new ArrayList<InetAddress>();
        for (var ip : raw) if (!answers.contains(ip)) answers.add(ip);
        if (answers.isEmpty()) throw new Unresolvable();

        var v4 = new ArrayList<InetAddress>();
        var v6 = new ArrayList<InetAddress>();
        for (var ip : answers) {
            if (blocked(ip)) continue;
            (ip.getAddress().length == 4 ? v4 : v6).add(ip);
        }
        v4.addAll(v6);
        return v4;
    }

    private static final int MAX_HOST_BYTES = 255;
    private static final int MAX_ADDRESSES = 256;

    /// `normalize_host` for a String host: ASCII, no NUL, not empty, at most 255 bytes, no zone.
    private static boolean normalHost(String host) {
        if (host.isEmpty() || host.length() > MAX_HOST_BYTES) return false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c > 0x7f || c == 0 || c == '%') return false;
        }
        return true;
    }

    private static boolean validHostSyntax(String host) {
        if (host.indexOf(':') >= 0 || legacyIpv4Shape(host) || ipLiteral(host) != null) return true;
        String h = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        for (String label : h.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63) return false;
            if (!isAlnum(label.charAt(0)) || !isAlnum(label.charAt(label.length() - 1))) return false;
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (!isAlnum(c) && c != '-') return false;
            }
        }
        return true;
    }

    private static boolean numericHostCandidate(String host) {
        return host.indexOf(':') >= 0 || legacyIpv4Shape(host);
    }

    private static boolean malformedNumericHostCandidate(String host) {
        if (host.indexOf(':') >= 0) return false;
        int start = 0;
        while (start < host.length() && (host.charAt(start) == '%' || host.charAt(start) == '/')) start++;
        int end = start;
        while (end < host.length() && host.charAt(end) != '%' && host.charAt(end) != '/') end++;
        String core = host.substring(start, end);
        boolean malformed = !core.equals(host) || core.isEmpty() || core.startsWith(".") || core.endsWith(".") || core.contains("..");
        return malformed && legacyIpv4Shape(core) && ipLiteral(host) == null;
    }

    /// 1 to 4 dot-separated (empty parts ignored) decimal or 0x-hex numbers.
    private static boolean legacyIpv4Shape(String text) {
        int parts = 0;
        for (String part : text.split("\\.", -1)) {
            if (part.isEmpty()) continue;
            parts++;
            if (part.startsWith("0x") || part.startsWith("0X")) {
                String hex = part.substring(2);
                if (hex.isEmpty() || !hex.chars().allMatch(Guard::isHex)) return false;
            } else if (!part.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return false;
            }
        }
        return parts >= 1 && parts <= 4;
    }

    /// `IPAddr.new(text)` for a single address: dotted-quad IPv4 (no leading zeros), or IPv6 with
    /// optional brackets.
    static byte[] ipLiteral(String text) {
        if (text.startsWith("[") && text.endsWith("]") && text.length() >= 2) return parseIpv6(text.substring(1, text.length() - 1));
        if (text.indexOf(':') >= 0) return parseIpv6(text);
        return parseStrictIpv4(text);
    }

    /// glibc's `getaddrinfo(..., AI_NUMERICHOST)`: `inet_aton` forms for IPv4, `inet_pton` for IPv6.
    private static byte[] getaddrinfoNumeric(String host) {
        return host.indexOf(':') >= 0 ? parseIpv6(host) : inetAton(host);
    }

    /// glibc `__inet_aton_exact`: 1-4 parts in decimal, octal (leading 0) or hex (0x); the last
    /// part fills the remaining bytes.
    static byte[] inetAton(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length == 0 || parts.length > 4) return null;
        long[] values = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) return null;
            String digits;
            int radix;
            if (part.startsWith("0x") || part.startsWith("0X")) {
                digits = part.substring(2);
                radix = 16;
            } else if (part.length() > 1 && part.startsWith("0")) {
                digits = part.substring(1);
                radix = 8;
            } else {
                digits = part;
                radix = 10;
            }
            for (int j = 0; j < digits.length(); j++) if (Character.digit(digits.charAt(j), radix) < 0) return null;
            long value = 0;
            for (int j = 0; j < digits.length(); j++) {
                value = value * radix + Character.digit(digits.charAt(j), radix);
                if (value > 0xffffffffL) return null;
            }
            values[i] = value;
        }
        int leading = values.length - 1;
        for (int i = 0; i < leading; i++) if (values[i] > 0xff) return null;
        int remainingBits = 32 - 8 * leading;
        long last = values[leading];
        if (remainingBits < 32 && last >= (1L << remainingBits)) return null;
        long address = last;
        for (int i = 0; i < leading; i++) address |= values[i] << (24 - 8 * i);
        return new byte[] {(byte) (address >> 24), (byte) (address >> 16), (byte) (address >> 8), (byte) address};
    }

    /// Four decimal octets without leading zeros (Rust's and Ruby's `IPAddr` strict form).
    static byte[] parseStrictIpv4(String text) {
        String[] octets = text.split("\\.", -1);
        if (octets.length != 4) return null;
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String o = octets[i];
            if (o.isEmpty() || o.length() > 3 || (o.length() > 1 && o.charAt(0) == '0')) return null;
            int value = 0;
            for (int j = 0; j < o.length(); j++) {
                char c = o.charAt(j);
                if (c < '0' || c > '9') return null;
                value = value * 10 + (c - '0');
            }
            if (value > 255) return null;
            out[i] = (byte) value;
        }
        return out;
    }

    /// An IPv6 address in text form (RFC 4291 section 2.2, no zone, optional trailing dotted
    /// quad), as 16 bytes; null when malformed.
    static byte[] parseIpv6(String text) {
        if (text.isEmpty() || text.indexOf('%') >= 0) return null;
        int doubleColon = text.indexOf("::");
        if (doubleColon >= 0 && text.indexOf("::", doubleColon + 1) >= 0) return null;
        List<Integer> head = new ArrayList<>();
        List<Integer> tail = new ArrayList<>();
        if (doubleColon >= 0) {
            if (!groups(text.substring(0, doubleColon), head, false)) return null;
            if (!groups(text.substring(doubleColon + 2), tail, true)) return null;
            if (head.size() + tail.size() > 7) return null;
        } else {
            if (!groups(text, head, true) || head.size() != 8) return null;
        }
        byte[] out = new byte[16];
        for (int i = 0; i < head.size(); i++) {
            out[2 * i] = (byte) (head.get(i) >> 8);
            out[2 * i + 1] = (byte) (int) head.get(i);
        }
        int offset = 8 - tail.size();
        for (int i = 0; i < tail.size(); i++) {
            out[2 * (offset + i)] = (byte) (tail.get(i) >> 8);
            out[2 * (offset + i) + 1] = (byte) (int) tail.get(i);
        }
        return out;
    }

    /// Colon-separated 16-bit groups; the last may be a dotted quad when `allowIpv4` is set.
    private static boolean groups(String text, List<Integer> out, boolean allowIpv4) {
        if (text.isEmpty()) return true;
        String[] parts = text.split(":", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (allowIpv4 && i == parts.length - 1 && part.indexOf('.') >= 0) {
                byte[] v4 = parseStrictIpv4(part);
                if (v4 == null) return false;
                out.add(((v4[0] & 0xff) << 8) | (v4[1] & 0xff));
                out.add(((v4[2] & 0xff) << 8) | (v4[3] & 0xff));
                continue;
            }
            if (part.isEmpty() || part.length() > 4 || !part.chars().allMatch(Guard::isHex)) return false;
            out.add(Integer.parseInt(part, 16));
        }
        return out.size() <= 8;
    }

    /// An address for 4 or 16 bytes. IPv4-mapped IPv6 addresses stay IPv6 (unlike
    /// `InetAddress.getByAddress`), so they are classified as such.
    public static InetAddress toInet(byte[] address) {
        try {
            return address.length == 16 ? Inet6Address.getByAddress(null, address, -1) : Inet4Address.getByAddress(address);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /// Parses an address literal (dotted quad, or IPv6 with or without brackets) without DNS.
    public static InetAddress parseAddress(String text) {
        byte[] bytes = ipLiteral(text);
        if (bytes == null) throw new IllegalArgumentException("not an IP address: " + text);
        return toInet(bytes);
    }

    // --- Classification (Surfguard.blocked_address?, default policy) -----------------------------

    private record Range(byte[] network, int prefix) {}

    private static Range v4(String address, int prefix) {
        return new Range(parseStrictIpv4(address), prefix);
    }

    private static Range v6(String address, int prefix) {
        return new Range(parseIpv6(address), prefix);
    }

    /// `Surfguard::DISALLOWED_IPV4`
    private static final List<Range> DISALLOWED_IPV4 = List.of(v4("0.0.0.0", 8), v4("10.0.0.0", 8), v4("100.64.0.0", 10),
        v4("127.0.0.0", 8), v4("168.63.129.16", 32), v4("169.254.0.0", 16), v4("172.16.0.0", 12), v4("192.0.0.0", 24),
        v4("192.0.2.0", 24), v4("192.88.99.0", 24), v4("192.168.0.0", 16), v4("198.18.0.0", 15), v4("198.51.100.0", 24),
        v4("203.0.113.0", 24), v4("224.0.0.0", 4), v4("240.0.0.0", 4));

    /// `Surfguard::DISALLOWED_IPV6`
    private static final List<Range> DISALLOWED_IPV6 = List.of(v6("::", 128), v6("100::", 64), v6("100:0:0:1::", 64), v6("2001::", 32),
        v6("2001:2::", 48), v6("2001:db8::", 32), v6("2002::", 16), v6("3fff::", 20), v6("5f00::", 16), v6("fec0::", 10), v6("ff00::", 8));

    /// `Surfguard::IANA_ALLOCATED_IPV6_UNICAST`
    private static final List<Range> IANA_ALLOCATED_IPV6_UNICAST = List.of(v6("2001::", 23), v6("2001:200::", 23), v6("2001:400::", 23),
        v6("2001:600::", 23), v6("2001:800::", 22), v6("2001:c00::", 23), v6("2001:e00::", 23), v6("2001:1200::", 23),
        v6("2001:1400::", 22), v6("2001:1800::", 23), v6("2001:1a00::", 23), v6("2001:1c00::", 22), v6("2001:2000::", 19),
        v6("2001:4000::", 23), v6("2001:4200::", 23), v6("2001:4400::", 23), v6("2001:4600::", 23), v6("2001:4800::", 23),
        v6("2001:4a00::", 23), v6("2001:4c00::", 23), v6("2001:5000::", 20), v6("2001:8000::", 19), v6("2001:a000::", 20),
        v6("2001:b000::", 20), v6("2002::", 16), v6("2003::", 18), v6("2400::", 12), v6("2410::", 12), v6("2600::", 12),
        v6("2610::", 23), v6("2620::", 23), v6("2630::", 12), v6("2800::", 12), v6("2a00::", 12), v6("2a10::", 12), v6("2c00::", 12));

    /// `Surfguard::GLOBALLY_REACHABLE_IETF_ASSIGNMENTS`
    private static final List<Range> GLOBALLY_REACHABLE_IETF_ASSIGNMENTS = List.of(v6("2001:3::", 32), v6("2001:4:112::", 48));
    private static final Range IETF_PROTOCOL_ASSIGNMENTS = v6("2001::", 23);
    private static final Range NAT64_WELL_KNOWN = v6("64:ff9b::", 96);
    private static final Range NAT64_LOCAL_USE = v6("64:ff9b:1::", 48);
    private static final Range IPV4_MAPPED = v6("::ffff:0:0", 96);
    private static final Range IPV4_TRANSLATABLE = v6("::ffff:0:0:0", 96);
    private static final Range IPV4_COMPATIBLE = v6("::", 96);
    private static final Range UNIQUE_LOCAL = v6("fc00::", 7);
    private static final Range LINK_LOCAL_V6 = v6("fe80::", 10);
    private static final byte[] LOOPBACK_V6 = parseIpv6("::1");

    private static boolean in(byte[] ip, Range range) {
        int prefix = range.prefix();
        byte[] network = range.network();
        int full = prefix / 8;
        for (int i = 0; i < full; i++) if (ip[i] != network[i]) return false;
        int bits = prefix % 8;
        if (bits == 0) return true;
        int mask = (0xff << (8 - bits)) & 0xff;
        return (ip[full] & mask) == (network[full] & mask);
    }

    private static boolean inAny(byte[] ip, List<Range> ranges) {
        for (var range : ranges) if (in(ip, range)) return true;
        return false;
    }

    /// `Surfguard.blocked_address?(ip)`
    public static boolean blocked(InetAddress address) {
        byte[] ip = address.getAddress();
        if (ip.length == 4) return inAny(ip, DISALLOWED_IPV4);
        if (in(ip, IPV4_MAPPED) || in(ip, IPV4_COMPATIBLE) || in(ip, NAT64_LOCAL_USE)) return true;
        if (in(ip, NAT64_WELL_KNOWN) || in(ip, IPV4_TRANSLATABLE)) {
            return inAny(new byte[] {ip[12], ip[13], ip[14], ip[15]}, DISALLOWED_IPV4);
        }
        if (inAny(ip, GLOBALLY_REACHABLE_IETF_ASSIGNMENTS)) return false;
        if (in(ip, UNIQUE_LOCAL) || java.util.Arrays.equals(ip, LOOPBACK_V6) || in(ip, LINK_LOCAL_V6) || in(ip, IETF_PROTOCOL_ASSIGNMENTS)) {
            return true;
        }
        if (inAny(ip, DISALLOWED_IPV6)) return true;
        return !inAny(ip, IANA_ALLOCATED_IPV6_UNICAST);
    }

    private static boolean isAlnum(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static boolean isHex(int c) {
        return Character.digit(c, 16) >= 0 && c < 0x80;
    }

}
