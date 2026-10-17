package campfire.integrations.net;

import java.io.IOException;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

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

    /// `PrivateNetworkGuard.resolve(hostname)`: the first public address.
    public static InetAddress resolve(Network.Resolver resolver, String host) throws GuardException {
        var addresses = resolvePublicIps(resolver, host);
        if (addresses.isEmpty()) throw new Violation(host);
        return addresses.getFirst();
    }

    /// `Surfguard.resolve_public_ips(host)`. Malformed input comes back empty rather than raising.
    public static List<InetAddress> resolvePublicIps(Network.Resolver resolver, String host) throws Unresolvable {
        if (host == null || !normalHost(host)) return List.of();
        List<InetAddress> raw;
        InetAddress literal = literal(host);
        if (literal != null) {
            raw = List.of(literal);
        } else if (NUMERIC.matcher(host).matches() || !ldhName(host)) {
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

    /// Dot-separated decimal or hex numbers that aren't an address `inet_aton` reads (`1.2.3.4.`,
    /// `09.1.1.1`, `256.1.1.1`): never a name to look up.
    private static final Pattern NUMERIC = Pattern.compile("\\.*(?:(?:\\d+|0[xX]\\p{XDigit}+)\\.+)*(?:\\d+|0[xX]\\p{XDigit}+)\\.*");

    /// `normalize_host` for a String host: ASCII, no NUL, not empty, at most 255 bytes, no zone.
    private static boolean normalHost(String host) {
        if (host.isEmpty() || host.length() > MAX_HOST_BYTES) return false;
        return host.chars().noneMatch(c -> c > 0x7f || c == 0 || c == '%');
    }

    /// Letters, digits and inner hyphens in labels of 1 to 63 characters, with an optional final dot.
    private static boolean ldhName(String host) {
        try {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /// An address literal the way `getaddrinfo(..., AI_NUMERICHOST)` reads one: IPv6 with or
    /// without brackets, or IPv4 in any `inet_aton` form. Null for anything else.
    static InetAddress literal(String host) {
        try {
            if (host.startsWith("[") && host.endsWith("]")) return ipv6(host.substring(1, host.length() - 1));
            if (host.indexOf(':') >= 0) return ipv6(host);
            return Inet4Address.ofPosixLiteral(host);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /// An IPv6 literal (no zone), as an `Inet6Address` even when it's IPv4-mapped: `ofLiteral`
    /// would turn `::ffff:10.0.0.1` into `10.0.0.1`, and mapped addresses are classified as IPv6.
    private static InetAddress ipv6(String text) {
        if (text.indexOf(':') < 0 || text.indexOf('%') >= 0) throw new IllegalArgumentException("not an IPv6 address: " + text);
        byte[] address = InetAddress.ofLiteral(text).getAddress();
        if (address.length == 4) {
            byte[] mapped = new byte[16];
            mapped[10] = mapped[11] = (byte) 0xff;
            System.arraycopy(address, 0, mapped, 12, 4);
            address = mapped;
        }
        try {
            return Inet6Address.getByAddress(null, address, -1);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /// Parses an address literal (IPv4, or IPv6 with or without brackets) without DNS.
    public static InetAddress parseAddress(String text) {
        InetAddress address = literal(text);
        if (address == null) throw new IllegalArgumentException("not an IP address: " + text);
        return address;
    }

    // --- Classification (Surfguard.blocked_address?, default policy) -----------------------------

    private record Range(byte[] network, int prefix) {}

    private static Range v4(String address, int prefix) {
        return new Range(Inet4Address.ofLiteral(address).getAddress(), prefix);
    }

    private static Range v6(String address, int prefix) {
        return new Range(ipv6(address).getAddress(), prefix);
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
    private static final byte[] LOOPBACK_V6 = ipv6("::1").getAddress();

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
        if (in(ip, UNIQUE_LOCAL) || Arrays.equals(ip, LOOPBACK_V6) || in(ip, LINK_LOCAL_V6) || in(ip, IETF_PROTOCOL_ASSIGNMENTS)) {
            return true;
        }
        if (inAny(ip, DISALLOWED_IPV6)) return true;
        return !inAny(ip, IANA_ALLOCATED_IPV6_UNICAST);
    }
}
