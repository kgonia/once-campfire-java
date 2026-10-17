package campfire.cable;

import static campfire.cable.CableTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import campfire.db.Times;
import campfire.rooms.Membership;
import campfire.rooms.Room;
import campfire.sessions.Session;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Campfire's channels (ports crates/campfire/src/channels/tests/channels_test.rs and
/// revocation_test.rs, themselves ports of reference/test/channels/**).
class CableChannelsTest {
    private CableTestSupport app;

    @BeforeEach
    void start() throws Exception {
        app = CableTestSupport.start();
    }

    @AfterEach
    void stop() throws Exception {
        app.close();
    }

    // -- ApplicationCable::Connection -------------------------------------------------------------------

    @Test
    void connectsWithAValidSessionCookie() throws Exception {
        var client = app.connect(app.david);
        client.confirm(identifier("channel", "HeartbeatChannel"));
    }

    @Test
    void rejectsAConnectionWithoutOrWithABadSessionCookie() throws Exception {
        for (String cookie : new String[] {null, app.cookieWithToken("-1", null), "session_token=forged--0000"}) {
            var client = app.connectWithCookie(cookie);
            assertEquals(List.of(UNAUTHORIZED), client.untilClosed());
        }
    }

    @Test
    void rejectsASessionCookieOnceItExpires() throws Exception {
        Session session = app.db.write(tx -> Session.start(tx, app.david, "test", "8.8.8.8"));
        Instant now = Times.now();
        String cookie = app.cookieWithToken(session.token(), now.plusSeconds(60));
        assertEquals(WELCOME, app.connectWithCookie(cookie).next());

        Times.freeze(now.plusSeconds(61));
        assertEquals(List.of(UNAUTHORIZED), app.connectWithCookie(cookie).untilClosed());
    }

    // -- HeartbeatChannel and ApplicationCable::Channel --------------------------------------------------

    @Test
    void heartbeatAndBaseChannelsConfirm() throws Exception {
        var client = app.connect(app.jz);
        client.confirm(identifier("channel", "HeartbeatChannel"));
        client.confirm(identifier("channel", "ApplicationCable::Channel"));
        client.assertSilent();
    }

    // -- RoomChannel ----------------------------------------------------------------------------------------

    @Test
    void roomChannelStreamsForMemberRoomsOnly() throws Exception {
        var client = app.connect(app.kevin);
        String member = roomIdentifier("RoomChannel", app.designers.id());
        client.confirm(member);
        client.reject(roomIdentifier("RoomChannel", app.watercooler.id()));
        client.reject(roomIdentifier("RoomChannel", -1));
        client.reject(identifier("channel", "RoomChannel"));
        // Params are cast like Active Record casts an id.
        String byString = identifier("channel", "RoomChannel", "room_id", String.valueOf(app.designers.id()));
        client.confirm(byString);

        app.cable.broadcast("room:" + app.roomGid(app.designers), "{\"hello\":1}");
        var received = new ArrayList<>(List.of(client.next(), client.next()));
        received.sort(null);
        var expected = new ArrayList<>(List.of(delivery(member, "{\"hello\":1}"), delivery(byString, "{\"hello\":1}")));
        expected.sort(null);
        assertEquals(expected, received);
        client.assertSilent();
    }

    // -- PresenceChannel (reference/test/channels/presence_channel_test.rb) -------------------------------

    @Test
    void presenceSubscribesAndMarksTheMembershipConnected() throws Exception {
        var client = app.connect(app.david);
        String reads = identifier("channel", "ReadRoomsChannel");
        client.confirm(reads);

        Membership membership = app.membership(app.designers, app.david);
        assertFalse(membership.isConnected(Times.now()));
        app.db.write(tx -> tx.update("UPDATE memberships SET unread_at = '2024-01-01 00:00:00' WHERE id = ?", membership.id()));

        String presence = roomIdentifier("PresenceChannel", app.designers.id());
        client.confirm(presence);
        assertEquals(delivery(reads, "{\"room_id\":" + app.designers.id() + "}"), client.next());

        Membership present = app.membership(app.designers, app.david);
        assertTrue(present.isConnected(Times.now()));
        assertEquals(1, present.connections());
        assertNull(present.unreadAt());

        client.unsubscribe(presence);
        eventually(() -> !app.membership(app.designers, app.david).isConnected(Times.now()));
        Membership absent = app.membership(app.designers, app.david);
        assertEquals(0, absent.connections());
        assertNull(absent.connectedAt());
    }

    @Test
    void presenceCountsConnectionsAndRefreshes() throws Exception {
        String presence = roomIdentifier("PresenceChannel", app.designers.id());
        var first = app.connect(app.david);
        var second = app.connect(app.david);
        first.confirm(presence);
        second.confirm(presence);
        assertEquals(2, app.membership(app.designers, app.david).connections());

        // `refresh` reconnects a membership that timed out.
        Times.freeze(Instant.now().plusSeconds(61));
        assertFalse(app.membership(app.designers, app.david).isConnected(Times.now()));
        first.perform(presence, "{\"action\":\"refresh\"}");
        eventually(() -> app.membership(app.designers, app.david).isConnected(Times.now()));
        assertEquals(1, app.membership(app.designers, app.david).connections());

        second.unsubscribe(presence);
        eventually(() -> app.membership(app.designers, app.david).connectedAt() == null);
    }

    @Test
    void presenceDisconnectsWhenTheSocketCloses() throws Exception {
        String presence = roomIdentifier("PresenceChannel", app.designers.id());
        var client = app.connect(app.david);
        client.confirm(presence);
        assertTrue(app.membership(app.designers, app.david).isConnected(Times.now()));
        client.ws.close();
        eventually(() -> app.membership(app.designers, app.david).connectedAt() == null);
    }

    @Test
    void presenceRejectsRoomsTheUserIsNotInWithoutTouchingMemberships() throws Exception {
        var client = app.connect(app.david);
        Membership before = app.membership(app.benderAndKevin, app.kevin);
        client.reject(roomIdentifier("PresenceChannel", app.benderAndKevin.id()));
        client.reject(roomIdentifier("PresenceChannel", -1));
        client.assertSilent();
        assertEquals(before, app.membership(app.benderAndKevin, app.kevin));
    }

    // -- ReadRoomsChannel and UnreadRoomsChannel ----------------------------------------------------------

    @Test
    void unreadRoomsStreamsOnlyTheSubscribersOwnStream() throws Exception {
        String unreads = identifier("channel", "UnreadRoomsChannel");
        var outsider = app.connect(app.jz);
        var member = app.connect(app.kevin);
        outsider.confirm(unreads);
        member.confirm(unreads);

        app.db.read(conn -> {
            app.broadcasts.unreadRoom(conn, app.benderAndKevin);
            return null;
        });
        assertEquals(delivery(unreads, "{\"roomId\":" + app.benderAndKevin.id() + "}"), member.next());
        member.assertSilent();
        outsider.assertSilent();
    }

    @Test
    void readRoomsStreamsTheUsersReads() throws Exception {
        String reads = identifier("channel", "ReadRoomsChannel");
        var client = app.connect(app.jason);
        client.confirm(reads);
        app.broadcasts.readRoom(app.jason, 7);
        app.broadcasts.readRoom(app.david, 8);
        assertEquals(delivery(reads, "{\"room_id\":7}"), client.next());
        client.assertSilent();
    }

    // -- TypingNotificationsChannel -----------------------------------------------------------------------

    @Test
    void typingNotificationsBroadcastStartAndStopToTheRoom() throws Exception {
        String typing = roomIdentifier("TypingNotificationsChannel", app.designers.id());
        var typist = app.connect(app.jz);
        var reader = app.connect(app.kevin);
        typist.confirm(typing);
        reader.confirm(typing);

        typist.perform(typing, "{\"action\":\"start\"}");
        String start = "{\"action\":\"start\",\"user\":{\"id\":" + app.jz + ",\"name\":\"JZ\"}}";
        assertEquals(delivery(typing, start), reader.next());
        assertEquals(delivery(typing, start), typist.next());

        typist.perform(typing, "{\"action\":\"stop\"}");
        assertEquals(delivery(typing, "{\"action\":\"stop\",\"user\":{\"id\":" + app.jz + ",\"name\":\"JZ\"}}"), reader.next());

        // Not an action: nothing is sent.
        typist.perform(typing, "{\"action\":\"dance\"}");
        reader.assertSilent();
        assertEquals(1 + 1, app.cable.deliver("typing_notifications:" + app.roomGid(app.designers), "{}"));
    }

    /// `safe_constantize` resolves "::TypingNotificationsChannel" to the class, whose broadcastings
    /// are named after the class alone, so both spellings share the room's typing stream.
    @Test
    void typingNotificationsReachTheRoomHoweverTheChannelIsSpelled() throws Exception {
        String prefixed = roomIdentifier("::TypingNotificationsChannel", app.designers.id());
        String plain = roomIdentifier("TypingNotificationsChannel", app.designers.id());
        var typist = app.connect(app.jz);
        var reader = app.connect(app.kevin);
        typist.confirm(prefixed);
        reader.confirm(plain);

        typist.perform(prefixed, "{\"action\":\"start\"}");
        String start = "{\"action\":\"start\",\"user\":{\"id\":" + app.jz + ",\"name\":\"JZ\"}}";
        assertEquals(delivery(plain, start), reader.next());
        assertEquals(delivery(prefixed, start), typist.next());

        reader.perform(plain, "{\"action\":\"stop\"}");
        String stop = "{\"action\":\"stop\",\"user\":{\"id\":" + app.kevin + ",\"name\":\"Kevin\"}}";
        assertEquals(delivery(prefixed, stop), typist.next());
        assertEquals(delivery(plain, stop), reader.next());
    }

    /// A subscription whose `subscribed` failed has no room: typing there is an error, not
    /// something that takes the connection down.
    @Test
    void typingOnAFailedSubscriptionLeavesTheConnectionUp() throws Exception {
        var client = app.connect(app.jz);
        String typing = roomIdentifier("TypingNotificationsChannel", app.designers.id());
        app.db.write(tx -> {
            tx.execute("ALTER TABLE rooms RENAME TO rooms_away");
            return null;
        });
        client.subscribe(typing);
        client.confirm(identifier("channel", "HeartbeatChannel"));
        app.db.write(tx -> {
            tx.execute("ALTER TABLE rooms_away RENAME TO rooms");
            return null;
        });
        client.perform(typing, "{\"action\":\"start\"}");
        client.confirm(identifier("channel", "ApplicationCable::Channel"));
    }

    @Test
    void typingNotificationsRejectNonMembers() throws Exception {
        var client = app.connect(app.jz);
        client.reject(roomIdentifier("TypingNotificationsChannel", app.watercooler.id()));
    }

    // -- RoomMessagesChannel (reference/test/channels/room_messages_channel_test.rb) -----------------------

    @Test
    void aMemberMaySubscribeToARoomsMessageStream() throws Exception {
        String signed = app.signedStreamName(app.roomGid(app.designers), "messages");
        String channel = identifier("channel", "RoomMessagesChannel", "signed_stream_name", signed);
        var kevin = app.connect(app.kevin);
        kevin.confirm(channel);

        app.broadcasts.streamTo(Broadcasts.roomMessages(app.designers), Broadcasts.actionTag("remove", "message_0001", null));
        assertEquals(delivery(channel, htmlJson("<turbo-stream action=\"remove\" target=\"message_0001\"></turbo-stream>")), kevin.next());

        String html = "<div id=\"m\">Hi &amp; \"bye\"</div>";
        app.broadcasts.streamTo(Broadcasts.roomMessages(app.designers), Broadcasts.actionTag("replace", "presentation_message_1", html,
            "maintain_scroll", "true"));
        assertEquals(delivery(channel, htmlJson("<turbo-stream maintain_scroll=\"true\" action=\"replace\" target=\"presentation_message_1\">"
            + "<template>" + html + "</template></turbo-stream>")), kevin.next());
    }

    @Test
    void roomMessageStreamsAreRejectedForEveryoneElse() throws Exception {
        String signed = app.signedStreamName(app.roomGid(app.designers), "messages");
        var bender = app.connect(app.bender);
        // A user who was never a member.
        bender.reject(identifier("channel", "RoomMessagesChannel", "signed_stream_name", signed));
        // Another room the user isn't in.
        bender.reject(identifier("channel", "RoomMessagesChannel", "signed_stream_name", app.signedStreamName(app.roomGid(app.hq), "messages")));

        var kevin = app.connect(app.kevin);
        // An unsigned stream name.
        kevin.reject(identifier("channel", "RoomMessagesChannel", "signed_stream_name", app.roomGid(app.designers) + ":messages"));
        // A missing stream name.
        kevin.reject(identifier("channel", "RoomMessagesChannel"));
        // A signed name that isn't a room's message stream.
        kevin.reject(identifier("channel", "RoomMessagesChannel", "signed_stream_name", app.signedStreamName("rooms")));
        // A room whose type changed since the name was signed (`Rooms::Open.find` of a closed room).
        String stale = Broadcasts.roomGid(app.designers.withType(Room.OPEN)).toParam();
        kevin.reject(identifier("channel", "RoomMessagesChannel", "signed_stream_name", app.signedStreamName(stale, "messages")));
        // A user GID in place of a room.
        kevin.reject(identifier("channel", "RoomMessagesChannel", "signed_stream_name",
            app.signedStreamName(Broadcasts.userGid(app.kevin).toParam(), "messages")));
        // A plain `Room` GID locates any room type.
        String room = campfire.rails.GlobalId.of("Room", app.designers.id()).toParam();
        kevin.confirm(identifier("channel", "RoomMessagesChannel", "signed_stream_name", app.signedStreamName(room, "messages")));
        // An unknown model raises NameError: no reply.
        String unknown = campfire.rails.GlobalId.of("Nope", 1).toParam();
        kevin.subscribe(identifier("channel", "RoomMessagesChannel", "signed_stream_name", app.signedStreamName(unknown, "messages")));
        kevin.assertSilent();
    }

    @Test
    void aRevokedMemberMayNotResubscribeWithAHarvestedStreamName() throws Exception {
        String signed = app.signedStreamName(app.roomGid(app.designers), "messages");
        String channel = identifier("channel", "RoomMessagesChannel", "signed_stream_name", signed);
        var kevin = app.connect(app.kevin);
        kevin.confirm(channel);

        revokeMembership(app.designers, app.kevin);
        kevin.untilClosed();

        app.connect(app.kevin).reject(channel);
    }

    // -- Turbo::StreamsChannel with RoomStreamsAreAuthorized ----------------------------------------------

    @Test
    void theStockTurboChannelRefusesRoomMessageStreamsButServesTheRoomList() throws Exception {
        var kevin = app.connect(app.kevin);
        kevin.reject(identifier("channel", "Turbo::StreamsChannel", "signed_stream_name",
            app.signedStreamName(app.roomGid(app.designers), "messages")));
        kevin.reject(identifier("channel", "Turbo::StreamsChannel", "signed_stream_name", "forged--0000"));
        kevin.reject(identifier("channel", "Turbo::StreamsChannel"));

        String rooms = identifier("channel", "Turbo::StreamsChannel", "signed_stream_name", app.signedStreamName("rooms"));
        kevin.confirm(rooms);
        app.broadcasts.roomRemove(app.designers);
        assertEquals(delivery(rooms, htmlJson("<turbo-stream action=\"remove\" target=\"list_rooms_closed_" + app.designers.id()
            + "\"></turbo-stream>")), kevin.next());
    }

    /// Public `subscribed` is an action in Ruby: performing it streams again.
    @Test
    void performingSubscribedStreamsTwiceLikeRuby() throws Exception {
        String unreads = identifier("channel", "UnreadRoomsChannel");
        var client = app.connect(app.kevin);
        client.confirm(unreads);
        client.perform(unreads, "{\"action\":\"subscribed\"}");
        client.assertSilent();

        app.db.read(conn -> {
            app.broadcasts.unreadRoom(conn, app.benderAndKevin);
            return null;
        });
        String expected = delivery(unreads, "{\"roomId\":" + app.benderAndKevin.id() + "}");
        assertEquals(expected, client.next());
        assertEquals(expected, client.next());
    }

    // -- Revocation (revocation_test.rs) ------------------------------------------------------------------

    private static final String DISCONNECT_RECONNECT = "{\"type\":\"disconnect\",\"reason\":\"remote\",\"reconnect\":true}";
    private static final String DISCONNECT_FOR_GOOD = "{\"type\":\"disconnect\",\"reason\":\"remote\",\"reconnect\":false}";

    /// Each room-carrying channel's identifier for the designers room, and the broadcasting it streams from.
    private List<String[]> roomChannels() {
        String gid = app.roomGid(app.designers);
        String signed = app.signedStreamName(gid, "messages");
        return List.of(
            new String[] {roomIdentifier("RoomChannel", app.designers.id()), "room:" + gid},
            new String[] {roomIdentifier("PresenceChannel", app.designers.id()), "presence:" + gid},
            new String[] {roomIdentifier("TypingNotificationsChannel", app.designers.id()), "typing_notifications:" + gid},
            new String[] {identifier("channel", "RoomMessagesChannel", "signed_stream_name", signed), gid + ":messages"});
    }

    /// `membership.destroy` → `after_destroy_commit { user.reset_remote_connections }`.
    private void revokeMembership(Room room, long userId) {
        app.db.write(tx -> Membership.revokeFrom(tx, room.id(), List.of(userId)));
        app.cable.disconnectUser(userId);
    }

    /// `User#deactivate` / `ban`: sessions gone, then `close_remote_connections(reconnect: false)`.
    private void deactivate(long userId) {
        app.db.write(tx -> {
            Session.deleteAllFor(tx, userId);
            tx.update("UPDATE users SET status = 1 WHERE id = ?", userId);
            return null;
        });
        app.cable.disconnectUser(userId, false);
    }

    private CableTestSupport.Client subscribedKevin(String cookie) throws Exception {
        var kevin = app.connectWithCookie(cookie);
        assertEquals(WELCOME, kevin.next());
        for (String[] channel : roomChannels()) kevin.confirm(channel[0]);
        return kevin;
    }

    private void assertNoDeliveries() throws InterruptedException {
        for (String[] channel : roomChannels()) eventually(() -> app.cable.deliver(channel[1], "{\"after\":\"revocation\"}") == 0);
    }

    @Test
    void removingTheMembershipDisconnectsAndResubscribingIsRejected() throws Exception {
        String cookie = app.cookieFor(app.kevin);
        var kevin = subscribedKevin(cookie);
        // PresenceChannel's `present` told kevin's reads stream, which kevin isn't subscribed to.
        kevin.assertSilent();

        revokeMembership(app.designers, app.kevin);
        assertEquals(List.of(DISCONNECT_RECONNECT), kevin.untilClosed());
        assertNoDeliveries();

        // The client reconnects and replays its subscriptions.
        var again = app.connectWithCookie(cookie);
        assertEquals(WELCOME, again.next());
        for (String[] channel : roomChannels()) again.reject(channel[0]);
        // The stock channel doesn't serve the stream either.
        again.reject(identifier("channel", "Turbo::StreamsChannel", "signed_stream_name",
            app.signedStreamName(app.roomGid(app.designers), "messages")));
        // Rooms kevin is still in keep working.
        again.confirm(roomIdentifier("RoomChannel", app.benderAndKevin.id()));
    }

    @Test
    void deactivatingDisconnectsForGood() throws Exception {
        String cookie = app.cookieFor(app.kevin);
        var kevin = subscribedKevin(cookie);
        deactivate(app.kevin);
        assertEquals(List.of(DISCONNECT_FOR_GOOD), kevin.untilClosed());
        assertNoDeliveries();
        assertEquals(List.of(UNAUTHORIZED), app.connectWithCookie(cookie).untilClosed());
    }

    @Test
    void onlyTheRevokedUsersConnectionsAreClosed() throws Exception {
        var kevin = subscribedKevin(app.cookieFor(app.kevin));
        var jz = app.connect(app.jz);
        String room = roomIdentifier("RoomChannel", app.designers.id());
        jz.confirm(room);

        revokeMembership(app.designers, app.kevin);
        kevin.untilClosed();

        app.cable.broadcast("room:" + app.roomGid(app.designers), "{\"still\":\"here\"}");
        assertEquals(delivery(room, "{\"still\":\"here\"}"), jz.next());
    }
}
