package campfire.view;

import campfire.App;
import campfire.db.Times;
import campfire.accounts.Account;
import campfire.users.User;
import campfire.richtext.Html;
import campfire.web.Paths;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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

    // -- Layout helpers ------------------------------------------------------------------------------

    /// `page_title_tag`
    public Safe pageTitleTag(String title) {
        return Tag.content("title", null, title == null ? "Campfire" : title);
    }

    /// `current_user_meta_tags`
    public Safe currentUserMetaTags() {
        if (user == null) return Safe.EMPTY;
        return Safe.concat(Tag.empty("meta", Attrs.of("name", "current-user-id", "content", user.id())),
            Tag.empty("meta", Attrs.of("name", "current-user-name", "content", user.name())));
    }

    /// `script_aware_action_cable_meta_tag`
    public Safe actionCableMetaTag() {
        return Tag.open("meta", Attrs.of("name", "action-cable-url", "content", Paths.CABLE));
    }

    public Safe vapidMetaTag() {
        return Tag.open("meta", Attrs.of("name", "vapid-public-key", "content", vapidPublicKey()));
    }

    /// `custom_styles_tag`: the account's CSS, unescaped.
    public Safe customStylesTag() {
        String styles = customStyles();
        return styles == null ? Safe.EMPTY : Tag.content("style", Attrs.of().data("turbo_track", "reload"), Safe.of(styles));
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

    // -- Assets ------------------------------------------------------------------------------------

    /// `asset_path(source)`: URLs and absolute paths pass through; logical paths are digested.
    public String assetPath(String source) {
        if (source.startsWith("/") || source.startsWith("data:") || source.startsWith("cid:")) return source;
        int scheme = source.indexOf("://");
        if (scheme > 0 && source.substring(0, scheme).chars().allMatch(c -> Character.isLetter(c) && c < 128 || c == '-')) return source;
        return asset(source);
    }

    /// `image_tag(source, options)`: options in order, then `src`, then `width`/`height` from `size`.
    public Safe imageTag(String source, Attrs options) {
        Attrs attrs = options == null ? new Attrs() : options.copy();
        Object size = attrs.remove("size");
        attrs.set("src", assetPath(source));
        if (size != null) {
            String s = size.toString();
            int x = s.indexOf('x');
            attrs.set("width", x > 0 ? s.substring(0, x) : s);
            attrs.set("height", x > 0 ? s.substring(x + 1) : s);
        }
        return Tag.empty("img", attrs);
    }

    public Safe imageTag(String source) {
        return imageTag(source, null);
    }

    /// `image_tag(source, aria: { hidden: "true" }, size:)`, the common icon.
    public Safe icon(String source, int size) {
        return imageTag(source, Attrs.of().aria("hidden", "true").set("size", size));
    }

    public Safe icon(String source, int size, String cssClass) {
        return imageTag(source, Attrs.of().aria("hidden", "true").set("size", size).set("class", cssClass));
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

    /// `avatar_tag(user, **options)`: the options go to the image.
    public Safe avatarTag(User u, Attrs imageOptions) {
        Attrs image = Attrs.of().aria("hidden", "true").set("size", 48);
        if (imageOptions != null) image.merge(imageOptions);
        return linkTo(Paths.user(u.id()), Attrs.of("title", u.title(), "class", "btn avatar").data("turbo_frame", "_top"),
            imageTag(avatarPath(u), image));
    }

    public Safe avatarTag(User u) {
        return avatarTag(u, null);
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

    /// `button_to_direct_room_with(user)`
    public Safe buttonToDirectRoomWith(long userId) {
        return buttonTo(roomsDirectsWithUsers(List.of(userId)), null, Attrs.of("class", "btn btn--primary full-width txt--large"),
            imageTag("messages.svg"));
    }

    public String freshAccountLogoPath() {
        return Paths.freshAccountLogo(account == null ? null : account.updatedAt(), null);
    }

    /// `account_logo_tag(style:)`; a nil style leaves a trailing space in the class.
    public Safe accountLogoTag(String style) {
        return Tag.content("figure", Attrs.of("class", "account-logo avatar " + (style == null ? "" : style)),
            imageTag(freshAccountLogoPath(), Attrs.of("alt", "Account logo", "size", 300)));
    }

    /// `profile_form_submit_button`
    public Safe profileFormSubmitButton() {
        return Tag.content("button", Attrs.of("class", "btn btn--reversed center txt-large", "type", "submit"),
            Safe.concat(icon("check.svg", 20), Tag.content("span", Attrs.of("class", "for-screen-reader"), "Save changes")));
    }

    /// `sidebar_turbo_frame_tag(src:) { content }`
    public Safe sidebarTurboFrameTag(String src, Object content) {
        Attrs attrs = Attrs.of().data("turbo_permanent", true, "controller", "rooms-list read-rooms turbo-frame", "rooms_list_unread_class",
            "unread", "action", Safe.of("presence:present@window->rooms-list#read read-rooms:read->rooms-list#read turbo:frame-load->rooms-list#loaded "
                + "refresh-room:visible@window->turbo-frame#reload"));
        attrs.set("id", "user_sidebar").set("src", src).set("target", "_top");
        return Tag.content("turbo-frame", attrs, content);
    }

    /// `user_filter_menu_tag { content }`
    public Safe userFilterMenuTag(Object content) {
        return Tag.content("menu", Attrs.of("class", "flex flex-column gap margin-none pad overflow-y constrain-height").data("controller",
            "filter", "filter_active_class", "filter--active", "filter_selected_class", "selected"), content);
    }

    /// `user_filter_search_tag`
    public Safe userFilterSearchTag() {
        return Tag.open("input", Attrs.of("type", "search", "id", "search", "autocorrect", "off", "autocomplete", "off", "data-1p-ignore", "true",
            "class", "input input--transparent full-width", "placeholder", "Filter…").data("action", "input->filter#filter"));
    }

    public static String curlTextLine(String url) {
        return "curl -d 'Hello!' " + url;
    }

    public static String curlUploadLine(String url) {
        return "curl -F \"attachment=@/path/to/file\" " + url;
    }

    // -- Rooms -------------------------------------------------------------------------------------

    /// `link_to_room(room, **attributes) { content }`: the room-list data attributes merge into the
    /// caller's `data:`, which keeps its place among the attributes.
    public Safe linkToRoom(long roomId, Attrs options, Object content) {
        Attrs attrs = new Attrs();
        boolean placed = false;
        if (options != null) {
            for (var entry : options.values.entrySet()) {
                if (entry.getKey().equals("data") && entry.getValue() instanceof Map<?, ?> data) {
                    roomListData(attrs, roomId);
                    for (var d : data.entrySet()) attrs.data(d.getKey(), d.getValue());
                    placed = true;
                } else {
                    attrs.set(entry.getKey(), entry.getValue());
                }
            }
        }
        if (!placed) roomListData(attrs, roomId);
        return linkTo(Paths.room(roomId), attrs, content);
    }

    private static void roomListData(Attrs attrs, long roomId) {
        attrs.data("rooms_list_target", "room", "room_id", roomId, "badge_dot_target", "unread", "sorted_list_target", "item");
    }

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

    /// `button_to_change_involvement(room, involvement)`; `paramKey` is "rooms_open" and friends.
    public Safe buttonToChangeInvolvement(long roomId, String paramKey, boolean direct, String involvement) {
        String labelId = domId(paramKey, roomId, "involvement_label");
        String url = withQuery(Paths.roomInvolvement(roomId), Map.of("involvement", nextInvolvement(direct, involvement)));
        Safe content = Safe.concat(icon("notification-bell-" + involvement + ".svg", 20),
            Tag.content("span", Attrs.of("class", "for-screen-reader", "id", labelId), humanizeInvolvement(involvement)));
        return buttonTo(url, "put", Attrs.of("role", "checkbox").aria("checked", true, "labelledby", labelId).set("tabindex", 0)
            .set("class", "btn " + involvement), content);
    }

    public Safe linkBack() {
        String back = referrer == null || referrer.equals(requestUrl) ? Paths.ROOT : referrer;
        return linkBackTo(back);
    }

    public Safe linkBackTo(String destination) {
        return linkTo(destination, Attrs.of("class", "btn"),
            Safe.concat(icon("arrow-left.svg", 20), Tag.content("span", Attrs.of("class", "for-screen-reader"), "Go Back")));
    }

    public Safe linkBackToLastRoomVisited() {
        Long room = lastRoomVisitedId();
        return linkBackTo(room == null ? Paths.ROOT : Paths.room(room));
    }

    public Safe versionBadge() {
        return Tag.content("span", Attrs.of("class", "version-badge"), appVersion());
    }

    // -- URL, link and form helpers ------------------------------------------------------------------

    /// `link_to(url, options) { content }`: `href` after the given options.
    public static Safe linkTo(String url, Attrs options, Object content) {
        Attrs attrs = options == null ? new Attrs() : options.copy();
        attrs.set("href", url);
        return Tag.content("a", attrs, content);
    }

    /// `link_to_if(condition, name, url, options)`
    public static Safe linkToIf(boolean condition, String text, String url, Attrs options) {
        return condition ? linkTo(url, options, text) : Safe.text(text);
    }

    /// `mail_to(email)`
    public static Safe mailTo(String email) {
        String encoded = java.net.URLEncoder.encode(email, StandardCharsets.UTF_8).replace("+", "%20").replace("%40", "@");
        return Tag.content("a", Attrs.of("href", "mailto:" + encoded), email);
    }

    /// `url_for`'s extra params: `Hash#to_query`, sorted by key; list values become `key[]=`.
    public static String withQuery(String path, Map<String, ?> params) {
        var keys = new ArrayList<>(params.keySet());
        java.util.Collections.sort(keys);
        var pairs = new ArrayList<String>();
        for (String key : keys) {
            Object value = params.get(key);
            if (value instanceof List<?> list) {
                for (Object v : list) pairs.add(cgiEscape(key + "[]") + "=" + cgiEscape(String.valueOf(v)));
            } else if (value != null) {
                pairs.add(cgiEscape(key) + "=" + cgiEscape(String.valueOf(value)));
            }
        }
        return pairs.isEmpty() ? path : path + "?" + String.join("&", pairs);
    }

    /// `CGI.escape`
    public static String cgiEscape(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("%7E", "~").replace("*", "%2A");
    }

    /// `rooms_directs_path(user_ids: [...])`
    public static String roomsDirectsWithUsers(List<Long> userIds) {
        return withQuery(Paths.ROOMS_DIRECTS, Map.of("user_ids", userIds));
    }

    public static String roomsDirectsWithUser(long userId) {
        return roomsDirectsWithUsers(List.of(userId));
    }

    /// `dom_id(record, prefix)`
    public static String domId(String model, Object id, String prefix) {
        return prefix == null ? model + "_" + id : prefix + "_" + model + "_" + id;
    }

    /// `button_to(url, method:, **options) { content }`: the form, its `_method` field, then the
    /// button with the options and `type="submit"`.
    public static Safe buttonTo(String url, String method, Attrs options, Object content) {
        Attrs button = options == null ? new Attrs() : options.copy();
        Object formClass = button.remove("form_class");
        Object methodOption = button.remove("method");
        if (method == null && methodOption != null) method = methodOption.toString();
        String m = method == null ? "post" : method.toLowerCase(Locale.ROOT);
        var out = new StringBuilder(256).append("<form");
        Attrs.of("class", formClass == null ? "button_to" : formClass, "method", m.equals("get") ? "get" : "post", "action", url).appendTo(out);
        out.append('>');
        if (m.equals("delete") || m.equals("patch") || m.equals("put")) out.append(methodTag(m));
        button.set("type", "submit");
        out.append("<button");
        button.appendTo(out);
        out.append('>');
        out.append(Safe.from(content));
        return Safe.of(out.append("</button></form>").toString());
    }

    /// `button_to(url, method:, ...) { content }` with the method among the options.
    public static Safe buttonTo(String url, Attrs options, Object content) {
        return buttonTo(url, null, options, content);
    }

    /// The hidden `_method` field.
    public static Safe methodTag(String method) {
        return Tag.empty("input", Attrs.of("type", "hidden", "name", "_method", "value", method));
    }

    /// `button_tag(options) { content }`: `name="button" type="submit"` merged with the options.
    public static Safe buttonTag(Attrs options, Object content) {
        Attrs attrs = Attrs.of("name", "button", "type", "submit");
        if (options != null) attrs.merge(options);
        return Tag.content("button", attrs, content);
    }

    /// `hidden_field_tag(name, value, options)`
    public static Safe hiddenFieldTag(String name, String value, Attrs options) {
        Attrs attrs = Attrs.of("type", "hidden", "name", name, "id", sanitizeToId(name), "value", value);
        if (options != null) attrs.merge(options);
        return Tag.empty("input", attrs);
    }

    static String sanitizeToId(String name) {
        var out = new StringBuilder();
        for (char c : name.replace("]", "").toCharArray()) {
            out.append(c < 128 && (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ':' || c == '.') ? c : '_');
        }
        return out.toString();
    }

    /// `form_with(url:)`
    public static Form formWith(String url) {
        return new Form(url);
    }

    /// `button_to_copy_to_clipboard(url) { content }`
    public static Safe buttonToCopyToClipboard(String url, Object content) {
        return Tag.content("button", Attrs.of("class", "btn").data("controller", "copy-to-clipboard", "action", "copy-to-clipboard#copy",
            "copy_to_clipboard_success_class", "btn--success", "copy_to_clipboard_content_value", url), content);
    }

    /// `link_to_zoom_qr_code(url) { content }`: the route takes the URL urlsafe-Base64 encoded.
    public static Safe linkToZoomQrCode(String url, Object content) {
        String path = Paths.qrCode(java.util.Base64.getUrlEncoder().encodeToString(url.getBytes(StandardCharsets.UTF_8)));
        return linkTo(path, Attrs.of("class", "btn").data("lightbox_target", "image", "action", "lightbox#open", "lightbox_url_value", path), content);
    }

    /// `web_share_session_button(url, title, text) { content }`
    public static Safe webShareSessionButton(String url, String title, String text, Object content) {
        return Tag.content("button", Attrs.of("class", "btn", "hidden", true).data("controller", "web-share", "action", "web-share#share",
            "web_share_url_value", url, "web_share_text_value", text, "web_share_title_value", title), content);
    }

    /// `local_datetime_tag(time, style:)`
    public static Safe localDatetimeTag(Instant time, String style, Attrs options) {
        Attrs attrs = options == null ? new Attrs() : options.copy();
        attrs.set("datetime", Times.iso8601(time)).data("local_time_target", style);
        return Tag.content("time", attrs, Safe.EMPTY);
    }

    /// `turbo_frame_tag(id, src:, target:, **attributes) { content }`: attributes, then id, src, target.
    public static Safe turboFrameTag(String id, String src, String target, Attrs attributes, Object content) {
        Attrs attrs = attributes == null ? new Attrs() : attributes.copy();
        attrs.set("id", id).set("src", src).set("target", target);
        return Tag.content("turbo-frame", attrs, content);
    }

    /// `turbo_stream_from(...)` with the signed name already computed.
    public static Safe turboStreamFrom(String signedStreamName) {
        return Tag.content("turbo-cable-stream-source", Attrs.of("channel", "Turbo::StreamsChannel", "signed-stream-name", signedStreamName), null);
    }

    public String signedStreamName(String... streamables) {
        return app.secrets().signedStreamName(streamables);
    }

    /// `turbo_page_requires_reload_tag`
    public static Safe turboPageRequiresReloadTag() {
        return Tag.open("meta", Attrs.of("name", "turbo-visit-control", "content", "reload"));
    }

    // -- Text --------------------------------------------------------------------------------------

    /// `truncate(text, length:, omission:)`
    public static String truncate(String text, int length, String omission) {
        if (text.codePointCount(0, text.length()) <= length) return text;
        int keep = Math.max(0, length - omission.codePointCount(0, omission.length()));
        return text.substring(0, text.offsetByCodePoints(0, keep)) + omission;
    }

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

    private static final Map<String, List<String[]>> TRANSLATIONS = loadTranslations();

    private static Map<String, List<String[]>> loadTranslations() {
        var map = new LinkedHashMap<String, List<String[]>>();
        try (var in = View.class.getResourceAsStream("/campfire/view/translations.tsv");
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", 3);
                if (parts.length == 3) map.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(new String[] {parts[1], parts[2]});
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return map;
    }

    private static final ConcurrentHashMap<String, Safe> TRANSLATION_BUTTONS = new ConcurrentHashMap<>();

    /// `translation_button(key)`
    public Safe translationButton(String key) {
        return TRANSLATION_BUTTONS.computeIfAbsent(key, k -> {
            List<String[]> entries = TRANSLATIONS.get(k);
            if (entries == null) throw new IllegalArgumentException("unknown translation key " + k);
            var items = new StringBuilder();
            for (String[] entry : entries) {
                items.append(Tag.content("dt", null, entry[0])).append(Tag.content("dd", Attrs.of("class", "margin-none"), entry[1]));
            }
            Safe summary = Tag.content("summary", Attrs.of("class", "btn", "tabindex", -1),
                Safe.concat(imageTag("globe.svg", Attrs.of("size", 20).aria("hidden", "true").set("class", "color-icon")),
                    Tag.content("span", Attrs.of("class", "for-screen-reader"), "Translate")));
            Safe menu = Tag.content("div", Attrs.of("class", "language-list-menu shadow").data("popup_target", "menu"),
                Tag.content("dl", Attrs.of("class", "language-list"), Safe.of(items.toString())));
            return Tag.content("details", Attrs.of("class", "position-relative").data("controller", "popup", "action",
                    "keydown.esc->popup#close toggle->popup#toggle click@document->popup#closeOnClickOutside", "popup_orientation_top_class",
                    "popup-orientation-top"), Safe.concat(summary, menu));
        });
    }
}
