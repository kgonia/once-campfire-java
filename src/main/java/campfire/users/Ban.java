package campfire.users;

import static campfire.db.schema.Tables.BANS;
import static org.jooq.impl.DSL.deleteFrom;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.insertInto;
import static org.jooq.impl.DSL.param;
import static org.jooq.impl.DSL.selectOne;

import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Sql;
import campfire.db.Times;
import java.net.InetAddress;
import java.time.Instant;

/// A row of `bans`: an IP address a banned user signed in from.
public final class Ban {
    private Ban() {}

    private static final String BANNED = Sql.render(selectOne().from(BANS).where(BANS.IP_ADDRESS.eq(param(BANS.IP_ADDRESS))).limit(inline(1)));
    private static final String INSERT = Sql.render(insertInto(BANS, BANS.CREATED_AT, BANS.IP_ADDRESS, BANS.UPDATED_AT, BANS.USER_ID)
        .values(param(BANS.CREATED_AT), param(BANS.IP_ADDRESS), param(BANS.UPDATED_AT), param(BANS.USER_ID)));
    private static final String DELETE_ALL_FOR = Sql.render(deleteFrom(BANS).where(BANS.USER_ID.eq(param(BANS.USER_ID))));

    public static boolean banned(Conn conn, String ipAddress) {
        return ipAddress != null && conn.exists(BANNED, ipAddress);
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
        tx.insert(INSERT, now, ipAddress, now, userId);
    }

    public static void deleteAllFor(Tx tx, long userId) {
        tx.update(DELETE_ALL_FOR, userId);
    }
}
