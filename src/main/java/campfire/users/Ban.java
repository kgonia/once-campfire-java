package campfire.users;

import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Times;
import java.net.InetAddress;
import java.time.Instant;

/// A row of `bans`: an IP address a banned user signed in from.
public final class Ban {
    private Ban() {}

    public static boolean banned(Conn conn, String ipAddress) {
        return ipAddress != null && conn.exists("SELECT 1 FROM \"bans\" WHERE \"ip_address\" = ? LIMIT 1", ipAddress);
    }

    /// `ip_address_is_public`: loopback, private and link-local addresses are never banned.
    public static boolean isPublic(String ipAddress) {
        InetAddress address = literal(ipAddress);
        return address != null && !(address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
            || address.isAnyLocalAddress() || isUniqueLocal(address));
    }

    /// An IP address literal (IPv6 optionally in brackets), or null. Never a DNS lookup: the text
    /// can come from a client's `X-Forwarded-For`.
    public static InetAddress literal(String text) {
        if (text == null) return null;
        try {
            return InetAddress.ofLiteral(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /// IPv6 unique local addresses, `fc00::/7`.
    public static boolean isUniqueLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    public static void create(Tx tx, long userId, String ipAddress) {
        Instant now = Times.now();
        tx.insert("INSERT INTO \"bans\" (\"created_at\", \"ip_address\", \"updated_at\", \"user_id\") VALUES (?, ?, ?, ?)", now, ipAddress, now, userId);
    }

    public static void deleteAllFor(Tx tx, long userId) {
        tx.update("DELETE FROM \"bans\" WHERE \"user_id\" = ?", userId);
    }
}
