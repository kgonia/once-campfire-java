package campfire.view;

import campfire.App;
import campfire.accounts.Account;
import campfire.users.User;
import campfire.web.Paths;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// What every template renders with (the Rails view context): who is signed in, the account, the
/// flash, the request's URLs, and the helpers from `app/helpers` that templates share. Built per
/// request by {@link Pages}, or detached for broadcasts rendered outside a request.
public final class View {
    public final App app;
    /// `Current.user`, or null.
    public final User user;
    public final Account account;
    public final boolean accountHasLogo;
    public final String flashNotice;
    public final String flashAlert;
    public final campfire.useragent.Platform platform;
    /// `request.base_url`, for the `*_url` helpers.
    public final String baseUrl;
    /// `request.url`, compared with the referrer by `link_back`.
    public final String requestUrl;
    public final String referrer;
    /// The `last_room` the user visited (`TrackedRoomVisit`), or null.
    private final java.util.function.Supplier<Long> lastRoomVisited;
    private Long lastRoomVisitedId;
    private boolean lastRoomVisitedLoaded;
    /// Render pages in turbo-rails' frame layout (a `Turbo-Frame` request).
    public boolean frameLayout;
    /// Set once the layout has linked the stylesheets, which the response then preloads.
    public boolean stylesheetsLinked;

    public View(App app, User user, Account account, boolean accountHasLogo, String flashNotice, String flashAlert, campfire.useragent.Platform platform,
                String baseUrl, String requestUrl, String referrer, java.util.function.Supplier<Long> lastRoomVisited) {
        this.app = app;
        this.user = user;
        this.account = account;
        this.accountHasLogo = accountHasLogo;
        this.flashNotice = flashNotice;
        this.flashAlert = flashAlert;
        this.platform = platform == null ? campfire.useragent.Platform.of(null) : platform;
        this.baseUrl = baseUrl;
        this.requestUrl = requestUrl;
        this.referrer = referrer;
        this.lastRoomVisited = lastRoomVisited;
    }

    /// `ApplicationController.render` outside a request: no user, no flash, URLs against `baseUrl`.
    public static View detached(App app, Account account, boolean accountHasLogo, String baseUrl) {
        return new View(app, null, account, accountHasLogo, null, null, null, baseUrl, baseUrl + "/", null, null);
    }

    // -- Context -----------------------------------------------------------------------------------

    /// The `last_room` the user visited (`TrackedRoomVisit`), looked up when first asked for.
    public Long lastRoomVisitedId() {
        if (!lastRoomVisitedLoaded) {
            lastRoomVisitedId = lastRoomVisited == null ? null : lastRoomVisited.get();
            lastRoomVisitedLoaded = true;
        }
        return lastRoomVisitedId;
    }

    /// Where `link_back_to_last_room_visited` goes: that room, else the root.
    public String lastRoomVisitedPath() {
        Long room = lastRoomVisitedId();
        return room == null ? Paths.ROOT : Paths.room(room);
    }

    /// Where `link_back` goes: the referrer, unless that's this very page.
    public String backPath() {
        return referrer == null || referrer.equals(requestUrl) ? Paths.ROOT : referrer;
    }

    public boolean canAdminister() {
        return user != null && user.canAdminister();
    }

    public boolean isCurrentUser(long userId) {
        return user != null && user.id() == userId;
    }

    public String asset(String logical) {
        return app.assets().path(logical);
    }

    /// `root_url` and friends: base URL plus path.
    public String url(String path) {
        return baseUrl + path;
    }

    public String appVersion() {
        return app.config().appVersion();
    }

    public String vapidPublicKey() {
        return app.config().vapidPublicKey().orElse(null);
    }

    public String customStyles() {
        return account == null || account.customStyles() == null || account.customStyles().isEmpty() ? null : account.customStyles();
    }

    public Safe stylesheetTags() {
        stylesheetsLinked = true;
        return Safe.of(app.assets().stylesheetTags());
    }

    public Safe importmapTags() {
        return Safe.of(app.assets().importmapTags());
    }

    /// `body_classes`
    public String bodyClasses(String bodyClass) {
        var classes = new ArrayList<String>(3);
        if (bodyClass != null) classes.add(bodyClass);
        if (canAdminister()) classes.add("admin");
        if (accountHasLogo) classes.add("account-has-logo");
        return String.join(" ", classes);
    }

    // -- Users -------------------------------------------------------------------------------------

    private static final ConcurrentHashMap<Long, String> AVATAR_TOKENS = new ConcurrentHashMap<>();

    /// `user.avatar_token`: the user's signed id for the avatar purpose (it never expires).
    public String avatarToken(long userId) {
        return AVATAR_TOKENS.computeIfAbsent(userId, id -> app.secrets().signedId("User", id, "avatar", null));
    }

    /// `fresh_user_avatar_path(user)`
    public String avatarPath(User u) {
        return Paths.freshUserAvatar(avatarToken(u.id()), u.updatedAt());
    }

    /// `Users::AvatarsHelper::AVATAR_COLORS`
    public static final List<String> AVATAR_COLORS = List.of("#AF2E1B", "#CC6324", "#3B4B59", "#BFA07A", "#ED8008", "#ED3F1C", "#BF1B1B",
        "#736B1E", "#D07B53", "#736356", "#AD1D1D", "#BF7C2A", "#C09C6F", "#698F9C", "#7C956B", "#5D618F", "#3B3633", "#67695E");

    /// `avatar_background_color(user)`: `Zlib.crc32(user.to_param)` picks the color.
    public static String avatarBackgroundColor(long userId) {
        var crc = new java.util.zip.CRC32();
        crc.update(Long.toString(userId).getBytes(StandardCharsets.US_ASCII));
        return AVATAR_COLORS.get((int) (crc.getValue() % AVATAR_COLORS.size()));
    }

    public String freshAccountLogoPath() {
        return Paths.freshAccountLogo(account == null ? null : account.updatedAt(), null);
    }

    public static String curlTextLine(String url) {
        return "curl -d 'Hello!' " + url;
    }

    public static String curlUploadLine(String url) {
        return "curl -F \"attachment=@/path/to/file\" " + url;
    }

    /// `mail_to(email)`'s href: the address URL-encoded, but with `@` and spaces as `mailto:` takes them.
    public static String mailtoUrl(String email) {
        return "mailto:" + java.net.URLEncoder.encode(email, StandardCharsets.UTF_8).replace("+", "%20").replace("%40", "@");
    }

    // -- Rooms -------------------------------------------------------------------------------------

    /// `HUMANIZE_INVOLVEMENT`
    public static String humanizeInvolvement(String involvement) {
        return switch (involvement) {
            case "mentions" -> "Notifying about @ mentions";
            case "everything" -> "Notifying about all messages";
            case "nothing" -> "Notifications are off";
            case "invisible" -> "Notifications are off and room invisible in sidebar";
            default -> "";
        };
    }

    /// `next_involvement_for(room, involvement:)`
    public static String nextInvolvement(boolean direct, String involvement) {
        List<String> order = direct ? List.of("everything", "nothing") : List.of("mentions", "everything", "nothing", "invisible");
        int i = order.indexOf(involvement);
        return i >= 0 && i + 1 < order.size() ? order.get(i + 1) : order.getFirst();
    }

    // -- URLs --------------------------------------------------------------------------------------

    /// `CGI.escape`
    public static String cgiEscape(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("%7E", "~").replace("*", "%2A");
    }

    /// `rooms_directs_path(user_ids: [userId])`
    public static String roomsDirectsWithUser(long userId) {
        return Paths.ROOMS_DIRECTS + "?user_ids%5B%5D=" + userId;
    }

    /// `dom_id(record, prefix)`
    public static String domId(String model, Object id, String prefix) {
        return prefix == null ? model + "_" + id : prefix + "_" + model + "_" + id;
    }

    /// `link_to_zoom_qr_code`'s target: the route takes the URL urlsafe-Base64 encoded.
    public static String qrCodePath(String url) {
        return Paths.qrCode(java.util.Base64.getUrlEncoder().encodeToString(url.getBytes(StandardCharsets.UTF_8)));
    }

    public String signedStreamName(String... streamables) {
        return app.secrets().signedStreamName(streamables);
    }

    // -- Text --------------------------------------------------------------------------------------

    /// `String#capitalize`
    public static String capitalize(String text) {
        if (text == null || text.isEmpty()) return "";
        int first = text.codePointAt(0);
        return new StringBuilder().appendCodePoint(Character.toUpperCase(first)).append(text.substring(Character.charCount(first)).toLowerCase(Locale.ROOT))
            .toString();
    }

    /// `Array#to_sentence`
    public static String toSentence(List<String> items, String twoWordsConnector) {
        return switch (items.size()) {
            case 0 -> "";
            case 1 -> items.getFirst();
            case 2 -> items.get(0) + twoWordsConnector + items.get(1);
            default -> String.join(", ", items.subList(0, items.size() - 1)) + ", and " + items.getLast();
        };
    }

    public static String toSentence(List<String> items) {
        return toSentence(items, " and ");
    }

    // -- Translations ------------------------------------------------------------------------------

    /// One line of a `translation_button` menu: the language's flag and the label in it.
    public record Translation(String flag, String text) {}

    private static final Map<String, List<Translation>> TRANSLATIONS = loadTranslations();

    private static Map<String, List<Translation>> loadTranslations() {
        var map = new LinkedHashMap<String, List<Translation>>();
        try (var in = View.class.getResourceAsStream("/campfire/view/translations.tsv");
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", 3);
                if (parts.length == 3) map.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(new Translation(parts[1], parts[2]));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return map;
    }

    /// The `translation_button(key)` menu.
    public static List<Translation> translations(String key) {
        List<Translation> entries = TRANSLATIONS.get(key);
        if (entries == null) throw new IllegalArgumentException("unknown translation key " + key);
        return entries;
    }
}
