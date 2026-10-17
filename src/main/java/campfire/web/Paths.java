package campfire.web;

import campfire.db.Times;
import campfire.messages.Message;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/// Path helpers mirroring `config/routes.rb`.
public final class Paths {
    private Paths() {}

    public static final String ROOT = "/";
    public static final String NEW_SESSION = "/session/new";
    public static final String SESSION = "/session";
    public static final String FIRST_RUN = "/first_run";
    public static final String ACCOUNT_EDIT = "/account/edit";
    public static final String ACCOUNT = "/account";
    public static final String ACCOUNT_BOTS = "/account/bots";
    public static final String NEW_ACCOUNT_BOT = "/account/bots/new";
    public static final String ACCOUNT_JOIN_CODE = "/account/join_code";
    public static final String ACCOUNT_LOGO = "/account/logo";
    public static final String ACCOUNT_CUSTOM_STYLES = "/account/custom_styles";
    public static final String EDIT_ACCOUNT_CUSTOM_STYLES = "/account/custom_styles/edit";
    public static final String ACCOUNT_USERS = "/account/users";
    public static final String USER_SIDEBAR = "/users/me/sidebar";
    public static final String USER_PROFILE = "/users/me/profile";
    public static final String USER_PUSH_SUBSCRIPTIONS = "/users/me/push_subscriptions";
    public static final String AUTOCOMPLETABLE_USERS = "/autocompletable/users";
    public static final String NEW_ROOMS_OPEN = "/rooms/opens/new";
    public static final String NEW_ROOMS_CLOSED = "/rooms/closeds/new";
    public static final String ROOMS_OPENS = "/rooms/opens";
    public static final String ROOMS_CLOSEDS = "/rooms/closeds";
    public static final String ROOMS_DIRECTS = "/rooms/directs";
    public static final String NEW_ROOMS_DIRECT = "/rooms/directs/new";
    public static final String SEARCHES = "/searches";
    public static final String CLEAR_SEARCHES = "/searches/clear";
    public static final String UNFURL_LINK = "/unfurl_link";
    public static final String WEBMANIFEST = "/webmanifest";
    public static final String SERVICE_WORKER = "/service-worker";
    public static final String CABLE = "/cable";

    public static String room(long roomId) {
        return "/rooms/" + roomId;
    }

    public static String roomMessages(long roomId) {
        return "/rooms/" + roomId + "/messages";
    }

    public static String roomMessage(long roomId, long messageId) {
        return "/rooms/" + roomId + "/messages/" + messageId;
    }

    public static String editRoomMessage(long roomId, long messageId) {
        return "/rooms/" + roomId + "/messages/" + messageId + "/edit";
    }

    public static String roomAtMessage(long roomId, long messageId) {
        return "/rooms/" + roomId + "/@" + messageId;
    }

    public static String roomRefresh(long roomId) {
        return "/rooms/" + roomId + "/refresh";
    }

    public static String roomInvolvement(long roomId) {
        return "/rooms/" + roomId + "/involvement";
    }

    public static String roomBotMessages(long roomId, String botKey) {
        return "/rooms/" + roomId + "/" + botKey + "/messages";
    }

    public static String editRoomsOpen(long roomId) {
        return "/rooms/opens/" + roomId + "/edit";
    }

    public static String editRoomsClosed(long roomId) {
        return "/rooms/closeds/" + roomId + "/edit";
    }

    public static String roomsDirect(long roomId) {
        return "/rooms/directs/" + roomId;
    }

    public static String messageBoosts(long messageId) {
        return "/messages/" + messageId + "/boosts";
    }

    public static String newMessageBoost(long messageId) {
        return "/messages/" + messageId + "/boosts/new";
    }

    public static String messageBoost(long messageId, long boostId) {
        return "/messages/" + messageId + "/boosts/" + boostId;
    }

    public static String user(long userId) {
        return "/users/" + userId;
    }

    public static String userBan(long userId) {
        return "/users/" + userId + "/ban";
    }

    public static String userAvatar(String avatarToken) {
        return "/users/" + avatarToken + "/avatar";
    }

    /// `fresh_user_avatar_path(user)`: busts caches when the user changes.
    public static String freshUserAvatar(String avatarToken, Instant updatedAt) {
        return userAvatar(avatarToken) + "?v=" + Times.number(updatedAt);
    }

    public static String accountUser(long userId) {
        return "/account/users/" + userId;
    }

    public static String accountBot(long botId) {
        return "/account/bots/" + botId;
    }

    public static String editAccountBot(long botId) {
        return "/account/bots/" + botId + "/edit";
    }

    public static String accountBotKey(long botId) {
        return "/account/bots/" + botId + "/key";
    }

    /// The logo URL changes with the account, so caches see a new logo. Query keys are in
    /// alphabetical order, as Rails' URL helpers write them.
    public static String freshAccountLogo(@Nullable Instant accountUpdatedAt, @Nullable String size) {
        var path = new StringBuilder(ACCOUNT_LOGO);
        String sep = "?";
        if (size != null) {
            path.append(sep).append("size=").append(size);
            sep = "&";
        }
        if (accountUpdatedAt != null) path.append(sep).append("v=").append(Times.number(accountUpdatedAt));
        return path.toString();
    }

    public static String join(String joinCode) {
        return "/join/" + joinCode;
    }

    public static String sessionTransfer(String transferId) {
        return "/session/transfers/" + transferId;
    }

    public static String qrCode(String id) {
        return "/qr_code/" + id;
    }

    public static String userPushSubscription(long id) {
        return "/users/me/push_subscriptions/" + id;
    }

    public static String userPushSubscriptionTestNotifications(long id) {
        return "/users/me/push_subscriptions/" + id + "/test_notifications";
    }

    public static String searches(String query) {
        return SEARCHES + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    public static String autocompletableUsers(long roomId) {
        return AUTOCOMPLETABLE_USERS + "?room_id=" + roomId;
    }

    public static String message(Message message) {
        return "/messages/" + message.id();
    }

    // -- Route helpers as templates call them (`root_path`, `searches_path`, ...) --------------------

    public static String session() {
        return SESSION;
    }

    public static String editAccount() {
        return ACCOUNT_EDIT;
    }

    public static String accountBots() {
        return ACCOUNT_BOTS;
    }

    public static String newAccountBot() {
        return NEW_ACCOUNT_BOT;
    }

    public static String accountJoinCode() {
        return ACCOUNT_JOIN_CODE;
    }

    public static String accountCustomStyles() {
        return ACCOUNT_CUSTOM_STYLES;
    }

    public static String editAccountCustomStyles() {
        return EDIT_ACCOUNT_CUSTOM_STYLES;
    }

    public static String userSidebar() {
        return USER_SIDEBAR;
    }

    public static String userProfile() {
        return USER_PROFILE;
    }

    public static String userPushSubscriptions() {
        return USER_PUSH_SUBSCRIPTIONS;
    }

    public static String autocompletableUsers() {
        return AUTOCOMPLETABLE_USERS;
    }

    public static String newRoomsOpen() {
        return NEW_ROOMS_OPEN;
    }

    public static String newRoomsClosed() {
        return NEW_ROOMS_CLOSED;
    }

    public static String roomsDirects() {
        return ROOMS_DIRECTS;
    }

    public static String newRoomsDirect() {
        return NEW_ROOMS_DIRECT;
    }

    public static String searches() {
        return SEARCHES;
    }

    public static String clearSearches() {
        return CLEAR_SEARCHES;
    }
}
