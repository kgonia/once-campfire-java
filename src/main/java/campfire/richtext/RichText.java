package campfire.richtext;

import campfire.rails.Base64s;
import campfire.rails.Json;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/// Campfire's Action Text pipeline: what a stored message body looks like on screen, in the
/// editor, and as plain text (`MessagesHelper#message_presentation`, `ContentFilters`,
/// `RichTextHelper#editable_body`, `ActionText::Content#to_plain_text`).
public final class RichText {
    private RichText() {}

    public static final String ATTACHMENT_TAG = "action-text-attachment";
    public static final String MENTION_CONTENT_TYPE = "application/vnd.campfire.mention";
    public static final String OPENGRAPH_EMBED_CONTENT_TYPE = "application/vnd.actiontext.opengraph-embed";
    private static final String TWITTER_AVATAR_URL_PREFIX = "https://pbs.twimg.com/profile_images";
    /// Content attachments render at most this deep inside one another; deeper ones are empty.
    static final int MAX_CONTENT_ATTACHMENT_DEPTH = 8;

    /// A mentionable user as attachments render it.
    public record MentionUser(long id, String name, String title, String attachableSgid, String userPath, String avatarPath) {}

    /// Looks up mentioned users: by verified SGID, and by the GID inside an SGID whose signature no
    /// longer checks out (a rotated `SECRET_KEY_BASE`), which Campfire accepts for users only.
    public interface Resolver {
        MentionUser locateSigned(String sgid);

        MentionUser findGid(String gid);
    }

    public record Context(Resolver resolver, String requestHost) {}

    /// Something Rails would have raised while rendering.
    static final class RenderException extends RuntimeException {
        RenderException(String message) {
            super(message, null, false, false);
        }
    }

    // -- Public pipeline --------------------------------------------------------------------------

    /// `auto_link h(TextMessagePresentationFilters.apply(message.body.body)), ...`; "" when Rails
    /// would have raised (and rescued).
    public static String presentation(String body, Context ctx) {
        try {
            Element content = load(body, ctx);
            content = Filters.apply(content, ctx);
            return AutoLink.autoLink(renderedWithLayout(content, ctx), Sanitizer.AUTO_LINK);
        } catch (RenderException | StackOverflowError e) {
            return "";
        }
    }

    /// The stored body rendered inside its `lexxy-content` layout, unfiltered and not auto-linked
    /// (what bots get); "" for no body or where Rails would have raised.
    public static String html(String body, Context ctx) {
        if (body == null) return "";
        try {
            return renderedWithLayout(load(body, ctx), ctx);
        } catch (RenderException | StackOverflowError e) {
            return "";
        }
    }

    /// `message.body.to_plain_text`
    public static String plainText(String body, Context ctx) {
        try {
            return toPlainText(load(body, ctx), ctx);
        } catch (RenderException | StackOverflowError e) {
            return "";
        }
    }

    /// Assigning a String to a rich text attribute stores the canonicalized content
    /// (`ActionText::Content.new(body, canonicalize: true).to_html`); the body as given when that raises.
    public static String canonical(String body, Context ctx) {
        try {
            return Html.innerHtml(load(body, ctx));
        } catch (RenderException | StackOverflowError e) {
            return body;
        }
    }

    /// The ids of users mentioned with a verified SGID, once each.
    public static List<Long> mentionedUserIds(String body, Context ctx) {
        var ids = new ArrayList<Long>();
        if (body == null) return ids;
        try {
            Element content = load(body, ctx);
            for (Element node : attachmentNodes(content)) {
                if (actionTextAttachable(node, ctx) instanceof UserMention(MentionUser user) && !ids.contains(user.id())) ids.add(user.id());
            }
        } catch (RenderException | StackOverflowError ignored) {
        }
        return ids;
    }

    /// The value the editor receives when editing (`editable_body`, then Lexxy's
    /// `render_custom_attachments_in`); null for a blank body.
    public static String editableValue(String body, Context ctx) {
        try {
            Element root = Html.parseFragment(Html.strip(body == null ? "" : body));
            for (Element node : attachmentNodes(root)) {
                Attachment attachment = attachmentFromNode(node, ctx);
                if (attachment.attachable() instanceof Missing) {
                    node.remove();
                    continue;
                }
                node.attr("content-type", contentTypeOf(attachment.attachable()));
                node.attr("content", renderAttachmentHtml(attachment, ctx, 0));
            }
            String editable = Html.innerHtml(root);
            if (Html.isBlank(editable)) return null;
            root = Html.parseFragment(Html.strip(editable));
            for (Element node : attachmentNodes(root)) {
                if (Html.isBlank(node.attr("url"))) {
                    Attachment attachment = attachmentFromNode(node, ctx);
                    node.attr("content", Json.encode(renderAttachmentHtml(attachment, ctx, 0)));
                }
            }
            return Html.innerHtml(root);
        } catch (RenderException | StackOverflowError e) {
            return null;
        }
    }

    /// `Webhook#without_recipient_mentions`
    public static String withoutRecipientMentions(String plainText, String recipientName) {
        return plainText.replace("@" + recipientName, "").replaceAll("^[\\p{Space}\\p{Z}]+|[\\p{Space}\\p{Z}]+$", "");
    }

    // -- Content ---------------------------------------------------------------------------------

    /// `ActionText::Content.new(html)`: canonicalized.
    static Element load(String html, Context ctx) {
        Element root = Html.parseFragment(Html.strip(html == null ? "" : html));
        convertTrixAttachments(root, ctx);
        for (Element node : attachmentNodes(root)) node.empty();
        for (Element gallery : attachmentGalleryNodes(root)) {
            replaceWithHtml(gallery, "<div>" + Html.innerHtml(gallery) + "</div>");
        }
        return root;
    }

    static List<Element> attachmentNodes(Element root) {
        List<Element> found = root.getElementsByTag(ATTACHMENT_TAG);
        // Document order puts `root` first when it is one itself; it isn't a descendant.
        return root.normalName().equals(ATTACHMENT_TAG) ? found.subList(1, found.size()) : found;
    }

    /// Replaces `node` with `html` parsed in a body context (Nokogiri's `replace` with a string).
    static void replaceWithHtml(Node node, String html) {
        Element fragment = Html.parseFragment(html);
        var children = new ArrayList<>(fragment.childNodes());
        for (Node child : children) node.before(child);
        node.remove();
    }

    static void setInnerHtml(Element element, String html) {
        element.empty();
        Element fragment = Html.parseFragment(html);
        for (Node child : new ArrayList<>(fragment.childNodes())) element.appendChild(child);
    }

    private static final String[][] TRIX_ATTRIBUTES = {{"sgid", "sgid"}, {"contentType", "content-type"}, {"url", "url"}, {"href", "href"},
        {"filename", "filename"}, {"filesize", "filesize"}, {"width", "width"}, {"height", "height"}, {"previewable", "previewable"},
        {"content", "content"}, {"caption", "caption"}, {"presentation", "presentation"}};

    /// Trix's `figure[data-trix-attachment]` becomes an `<action-text-attachment>`.
    private static void convertTrixAttachments(Element root, Context ctx) {
        for (Element node : root.select("[data-trix-attachment]")) {
            var attributes = new java.util.LinkedHashMap<String, Object>();
            for (String name : List.of("data-trix-attachment", "data-trix-attributes")) {
                if (!node.hasAttr(name)) continue;
                Object parsed = Json.parseOrNull(stripJsonComments(node.attr(name)));
                if (parsed == null || Boolean.FALSE.equals(parsed)) continue;
                if (!(parsed instanceof Map<?, ?> map)) throw new RenderException("NoMethodError: merge");
                for (var entry : map.entrySet()) {
                    for (String[] pair : TRIX_ATTRIBUTES) if (pair[0].equals(entry.getKey())) attributes.put(pair[0], entry.getValue());
                }
            }
            var element = new Element(ATTACHMENT_TAG);
            for (String name : Sanitizer.ATTACHMENT_ATTRIBUTES) {
                for (String[] pair : TRIX_ATTRIBUTES) {
                    if (pair[1].equals(name) && attributes.containsKey(pair[0])) element.attr(name, jsonToS(attributes.get(pair[0])));
                }
            }
            if (element.attributes().isEmpty()) {
                node.remove();
            } else {
                attachmentFromNode(element, ctx);
                replaceWithHtml(node, Html.outerHtml(element));
            }
        }
    }

    /// The json gem accepts `/* */` and `//` comments outside strings.
    static String stripJsonComments(String s) {
        var out = new StringBuilder(s.length());
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < s.length()) out.append(s.charAt(++i));
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
                out.append(c);
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                if (end < 0) return "";
                i = end + 1;
                out.append(' ');
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                int end = s.indexOf('\n', i);
                i = end < 0 ? s.length() : end;
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String jsonToS(Object value) {
        return switch (value) {
            case null -> "";
            case String s -> s;
            default -> Json.generate(value);
        };
    }

    private static boolean isGalleryAttachment(Node node) {
        return node instanceof Element e && e.normalName().equals(ATTACHMENT_TAG) && "gallery".equals(e.attr("presentation"));
    }

    /// `div:has(A + A)` for gallery attachments A, whose children are only those or spaces.
    static List<Element> attachmentGalleryNodes(Element root) {
        var out = new ArrayList<Element>();
        for (Element div : root.getElementsByTag("div")) {
            boolean adjacent = false;
            for (Element e : div.getAllElements()) {
                if (e != div && isGalleryAttachment(e)) {
                    Element previous = e.previousElementSibling();
                    if (previous != null && isGalleryAttachment(previous)) {
                        adjacent = true;
                        break;
                    }
                }
            }
            if (!adjacent) continue;
            boolean only = true;
            for (Node child : div.childNodes()) {
                if (child instanceof TextNode t) {
                    if (!t.getWholeText().chars().allMatch(c -> c == '\n' || c == ' ')) only = false;
                } else if (!isGalleryAttachment(child)) {
                    only = false;
                }
            }
            if (only) out.add(div);
        }
        return out;
    }

    static String toPlainText(Element content, Context ctx) {
        Element root = content.clone();
        for (Element node : attachmentNodes(root)) {
            sanitizeContentAttribute(node);
            Attachment attachment = attachmentFromNode(node, ctx);
            switch (attachment.attachable()) {
                case ContentAttachable(String html) -> {
                    Element fragment = load(html, ctx);
                    for (Node child : new ArrayList<>(fragment.childNodes())) node.before(child);
                    node.remove();
                }
                case Attachable other -> replaceWithHtml(node, plainTextRepresentation(attachment));
            }
        }
        return PlainText.of(root);
    }

    private static String plainTextRepresentation(Attachment attachment) {
        String caption = attachment.caption();
        return switch (attachment.attachable()) {
            case UserMention(MentionUser user) -> "@" + user.name();
            case OpengraphEmbed e -> "";
            case RemoteImage r -> "[" + (caption != null ? caption : "Image") + "]";
            case RemoteVideo v -> "[" + (caption != null ? caption : v.filename() != null ? v.filename() : "Video") + "]";
            case Missing m -> caption == null ? "" : caption;
            case ContentAttachable c -> c.content();
        };
    }

    /// `render_action_text_content`: attachments rendered, then Action Text's allowlist.
    static String render(Element content, Context ctx, int depth) {
        Element root = content.clone();
        for (Element node : attachmentNodes(root)) {
            sanitizeContentAttribute(node);
            Attachment attachment = attachmentFromNode(node, ctx);
            Element full = withFullAttributes(node, attachment.attachable());
            String caption = full.hasAttr("caption") && !Html.isBlank(full.attr("caption")) ? full.attr("caption") : null;
            setInnerHtml(full, renderAttachmentHtml(new Attachment(attachment.attachable(), caption), ctx, depth));
            replaceWithHtml(node, Html.outerHtml(full));
        }
        for (Element gallery : attachmentGalleryNodes(root)) {
            var members = new ArrayList<Element>();
            for (Element e : gallery.getAllElements()) if (e != gallery && isGalleryAttachment(e)) members.add(e);
            var rendered = new StringBuilder();
            for (Element member : members) {
                Attachment attachment = attachmentFromNode(member, ctx);
                Element full = withFullAttributes(member, attachment.attachable());
                setInnerHtml(full, renderAttachmentHtml(attachment, ctx, depth));
                rendered.append(Html.outerHtml(full));
            }
            replaceWithHtml(gallery, "<div class=\"attachment-gallery attachment-gallery--" + members.size() + "\">\n  " + rendered + "\n</div>");
        }
        return Sanitizer.sanitize(Html.innerHtml(root), Sanitizer.ACTION_TEXT);
    }

    /// `Content#to_s` with Campfire's `lexxy-content` layout.
    static String renderedWithLayout(Element content, Context ctx) {
        return "<div class=\"lexxy-content\">\n  " + render(content, ctx, 0) + "\n</div>\n";
    }

    private static void sanitizeContentAttribute(Element node) {
        if (!node.hasAttr("content")) return;
        String content = node.attr("content");
        node.removeAttr("content");
        String sanitized = Sanitizer.sanitize(content, Sanitizer.ACTION_TEXT);
        if (!Html.isBlank(sanitized)) node.attr("content", sanitized);
    }

    private static Element withFullAttributes(Element node, Attachable attachable) {
        var element = new Element(ATTACHMENT_TAG);
        for (String name : Sanitizer.ATTACHMENT_ATTRIBUTES) {
            String value;
            if (attachable instanceof UserMention(MentionUser user) && name.equals("sgid")) {
                value = node.hasAttr("sgid") ? node.attr("sgid") : user.attachableSgid();
            } else if (attachable instanceof UserMention && name.equals("content-type")) {
                value = MENTION_CONTENT_TYPE;
            } else {
                value = node.hasAttr(name) ? node.attr(name) : null;
            }
            if (value != null) element.attr(name, value);
        }
        if (element.attributes().isEmpty()) throw new RenderException("NoMethodError: node for nil");
        return element;
    }

    // -- Attachables -------------------------------------------------------------------------------

    sealed interface Attachable permits UserMention, OpengraphEmbed, ContentAttachable, RemoteImage, RemoteVideo, Missing {}

    record UserMention(MentionUser user) implements Attachable {}

    record OpengraphEmbed(String href, String url, String filename, String description) implements Attachable {
        boolean twitterAvatar() {
            return url != null && url.startsWith(TWITTER_AVATAR_URL_PREFIX);
        }
    }

    record ContentAttachable(String content) implements Attachable {}

    record RemoteImage(String url, String width, String height) implements Attachable {}

    record RemoteVideo(String url, String contentType, String width, String height, String filename) implements Attachable {}

    record Missing() implements Attachable {}

    record Attachment(Attachable attachable, String caption) {}

    private static String contentTypeOf(Attachable attachable) {
        return switch (attachable) {
            case UserMention u -> MENTION_CONTENT_TYPE;
            case OpengraphEmbed e -> OPENGRAPH_EMBED_CONTENT_TYPE;
            default -> throw new RenderException("NoMethodError: attachable_content_type");
        };
    }

    static Attachment attachmentFromNode(Element node, Context ctx) {
        String caption = node.hasAttr("caption") && !Html.isBlank(node.attr("caption")) ? node.attr("caption") : null;
        return new Attachment(attachableFromNode(node, ctx), caption);
    }

    private static Attachable attachableFromNode(Element node, Context ctx) {
        OpengraphEmbed embed = opengraphEmbedFromNode(node, ctx);
        if (embed != null) return embed;
        MentionUser user = fromPossiblyExpiredSgid(node.hasAttr("sgid") ? node.attr("sgid") : null, ctx);
        if (user != null) return new UserMention(user);
        return actionTextAttachable(node, ctx);
    }

    private static final Pattern IMAGE_CONTENT_TYPE = Pattern.compile("(?m)^image(/.+|$)");
    private static final Pattern VIDEO_CONTENT_TYPE = Pattern.compile("(?m)^video(/.+|$)");

    static Attachable actionTextAttachable(Element node, Context ctx) {
        if (node.hasAttr("sgid")) {
            MentionUser user = ctx.resolver().locateSigned(node.attr("sgid"));
            if (user != null) return new UserMention(user);
        }
        String contentType = node.hasAttr("content-type") ? node.attr("content-type") : "";
        if (node.hasAttr("content") && contentType.contains("html") && !Html.isBlank(node.attr("content"))) {
            return new ContentAttachable(node.attr("content"));
        }
        if (node.hasAttr("url")) {
            String width = node.hasAttr("width") ? node.attr("width") : null;
            String height = node.hasAttr("height") ? node.attr("height") : null;
            if (IMAGE_CONTENT_TYPE.matcher(contentType).find()) return new RemoteImage(node.attr("url"), width, height);
            if (VIDEO_CONTENT_TYPE.matcher(contentType).find()) {
                return new RemoteVideo(node.attr("url"), contentType, width, height, node.hasAttr("filename") ? node.attr("filename") : null);
            }
        }
        return new Missing();
    }

    private static final Pattern MARSHALED_GID = Pattern.compile("(gid://campfire/[^/]+/[0-9]+)");

    /// `attachable_from_possibly_expired_sgid` (lib/rails_ext/action_text_attachables.rb).
    private static MentionUser fromPossiblyExpiredSgid(String sgid, Context ctx) {
        if (sgid == null) return null;
        String[] parts = sgid.split("--");
        if (parts.length == 0 || parts[0].isEmpty()) return null;
        byte[] decoded = decodeBase64(parts[0]);
        Object json = Json.parseOrNull(new String(decoded, StandardCharsets.UTF_8));
        if (json == null) throw new RenderException("JSON::ParserError");
        if (!(json instanceof Map<?, ?> map)) throw new RenderException("NoMethodError: dig");
        Object rails = map.get("_rails");
        if (rails != null && !(rails instanceof Map<?, ?>)) throw new RenderException("TypeError: dig");
        String gid = null;
        if (rails instanceof Map<?, ?> r) {
            Object data = r.get("data");
            Object message = r.get("message");
            if (data != null && !Boolean.FALSE.equals(data)) {
                gid = data instanceof String s ? s : null;
            } else if (message != null && !Boolean.FALSE.equals(message)) {
                if (!(message instanceof String m)) throw new RenderException("NoMethodError: unpack1");
                Matcher matcher = MARSHALED_GID.matcher(new String(decodeBase64(m), StandardCharsets.ISO_8859_1));
                if (matcher.find()) gid = matcher.group(1);
            }
        }
        return gid == null ? null : ctx.resolver().findGid(gid);
    }

    private static byte[] decodeBase64(String message) {
        byte[] decoded = Base64s.strictDecode(message);
        if (decoded == null) decoded = Base64s.urlsafeDecode(message);
        if (decoded == null) throw new RenderException("ArgumentError: invalid base64");
        return decoded;
    }

    static OpengraphEmbed opengraphEmbedFromNode(Element node, Context ctx) {
        if (!node.hasAttr("content-type") || !node.attr("content-type").contains(OPENGRAPH_EMBED_CONTENT_TYPE)) return null;
        String host = ctx.requestHost() == null ? "" : ctx.requestHost();
        if (node.hasAttr("filename") && !Html.isBlank(node.attr("filename"))) {
            return new OpengraphEmbed(webUrl(attr(node, "href"), host), webUrl(attr(node, "url"), host), node.attr("filename"), attr(node, "caption"));
        }
        Element fragment = Html.parseFragment(node.attr("content"));
        Element title = fragment.selectFirst(".og-embed__title");
        Element link = title == null ? null : title.selectFirst("a");
        Element image = fragment.selectFirst(".og-embed__image img");
        Element description = fragment.selectFirst(".og-embed__description");
        Element named = link != null ? link : title;
        return new OpengraphEmbed(webUrl(link == null ? null : attr(link, "href"), host), webUrl(image == null ? null : attr(image, "src"), host),
            named == null ? null : Html.strip(named.wholeText()), description == null ? null : Html.strip(description.wholeText()));
    }

    private static String attr(Element e, String name) {
        return e.hasAttr(name) ? e.attr(name) : null;
    }

    /// An absolute http(s) URL on a named host other than this Campfire's, or null.
    static String webUrl(String value, String requestHost) {
        if (value == null || Html.isBlank(value)) return null;
        URI uri;
        try {
            uri = new URI(value);
        } catch (Exception e) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return null;
        String host = uri.getRawAuthority() == null ? null : uri.getHost();
        if (host == null && uri.getRawAuthority() != null) {
            String authority = uri.getRawAuthority();
            int at = authority.lastIndexOf('@');
            host = authority.substring(at + 1).replaceFirst(":\\d*$", "");
        }
        if (host == null || !namedHost(host)) return null;
        return canonicalHost(host).equals(canonicalHost(requestHost)) ? null : value;
    }

    private static boolean namedHost(String host) {
        if (Html.isBlank(host) || host.contains("%") || !host.contains(".")) return false;
        String trimmed = host.replaceAll("\\.+$", "");
        if (trimmed.isEmpty()) throw new RenderException("NoMethodError: match?");
        String label = trimmed.substring(trimmed.lastIndexOf('.') + 1);
        return label.chars().anyMatch(c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) && !label.toLowerCase(Locale.ROOT).startsWith("0x");
    }

    private static String canonicalHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }

    // -- Attachment partials -------------------------------------------------------------------------

    static String renderAttachmentHtml(Attachment attachment, Context ctx, int depth) {
        String html = switch (attachment.attachable()) {
            case UserMention(MentionUser user) -> renderMention(user);
            case OpengraphEmbed embed -> renderOpengraphEmbed(embed);
            case Missing m -> "☒";
            case ContentAttachable(String content) -> {
                String inner = depth >= MAX_CONTENT_ATTACHMENT_DEPTH ? "" : render(load(content, ctx), ctx, depth + 1) + "\n";
                yield "<figure class=\"attachment attachment--content\">\n  " + inner + "\n</figure>\n";
            }
            case RemoteImage(String url, String width, String height) -> {
                var out = new StringBuilder("<figure class=\"attachment attachment--preview\">\n  ").append(imageTag(url, width, height)).append('\n');
                caption(out, attachment.caption());
                yield out.append("</figure>\n").toString();
            }
            case RemoteVideo v -> {
                var out = new StringBuilder("<figure class=\"attachment attachment--preview attachment--video\">\n  <video controls=\"controls\"");
                if (v.width() != null) out.append(" width=\"").append(Html.escape(v.width())).append('"');
                if (v.height() != null) out.append(" height=\"").append(Html.escape(v.height())).append('"');
                out.append(">\n    <source src=\"").append(Html.escape(v.url())).append("\" type=\"").append(Html.escape(v.contentType())).append("\">\n</video>");
                caption(out, attachment.caption());
                yield out.append("</figure>\n").toString();
            }
        };
        return html.endsWith("\n") ? html.substring(0, html.length() - 1) : html;
    }

    private static void caption(StringBuilder out, String caption) {
        if (caption != null) {
            out.append("    <figcaption class=\"attachment__caption\">\n      ").append(Html.escape(caption)).append("\n    </figcaption>\n");
        }
    }

    /// `users/_mention.html.erb`
    public static String renderMention(MentionUser user) {
        return "<span class=\"mention\" sgid=\"" + Html.escape(user.attachableSgid()) + "\"><a title=\"" + Html.escape(user.title())
            + "\" class=\"btn avatar\" data-turbo-frame=\"_top\" href=\"" + Html.escape(user.userPath()) + "\"><img aria-hidden=\"true\" src=\""
            + Html.escape(user.avatarPath()) + "\" width=\"48\" height=\"48\" /></a> " + Html.escape(user.name()) + "</span>\n";
    }

    /// `action_text/attachables/_opengraph_embed.html.erb`
    static String renderOpengraphEmbed(OpengraphEmbed embed) {
        String title;
        if (embed.href() != null) {
            String text = embed.filename() != null ? Html.escape(Html.truncate(embed.filename(), 280, "…")) : Html.escape(embed.href());
            title = "<a rel=\"noreferrer\" target=\"_blank\" href=\"" + Html.escape(embed.href()) + "\">" + text + "</a>";
        } else if (embed.filename() != null) {
            title = Html.escape(Html.truncate(embed.filename(), 280, "…"));
        } else {
            title = "";
        }
        var out = new StringBuilder("<figure class=\"attachment attachment--content attachment--og\">\n  <actiontext-opengraph-embed>\n    <div class=\"og-embed gap ")
            .append(embed.twitterAvatar() ? "og-embed--twitter-avatar" : "")
            .append("\">\n      <div class=\"og-embed__content\">\n        <div class=\"og-embed__title\">\n          ").append(title)
            .append("\n        </div>\n        <div class=\"og-embed__description\">")
            .append(Html.escape(Html.truncate(embed.description() == null ? "" : embed.description(), 560, "…")))
            .append("</div>\n      </div>\n");
        if (embed.url() != null) {
            out.append("        <div class=\"og-embed__image\">\n          <img src=\"").append(Html.escape(embed.url()))
                .append("\" class=\"image center\" alt=\"\">\n        </div>\n");
        }
        return out.append("    </div>\n  </actiontext-opengraph-embed>\n</figure>\n").toString();
    }

    private static final Pattern ASSET_URI = Pattern.compile("(?mi)^[-a-z]+://|^(?:cid|data):|^//");

    private static String imageTag(String url, String width, String height) {
        String src;
        if (Html.isBlank(url)) src = "";
        else if (ASSET_URI.matcher(url).find() || url.startsWith("/")) src = url;
        else throw new RenderException("Propshaft::MissingAssetError");
        var out = new StringBuilder("<img");
        if (width != null) out.append(" width=\"").append(Html.escape(width)).append('"');
        if (height != null) out.append(" height=\"").append(Html.escape(height)).append('"');
        return out.append(" src=\"").append(Html.escape(src)).append("\" />").toString();
    }

    // -- Content filters ---------------------------------------------------------------------------

    static final class Filters {
        private Filters() {}

        static Element apply(Element content, Context ctx) {
            content = removeSoloUnfurledLinkText(content, ctx);
            sanitizeTags(content);
            return Html.parseFragment(Html.strip(Sanitizer.sanitize(Html.innerHtml(content), Sanitizer.CONTENT_FILTER)));
        }

        /// `ContentFilters::RemoveSoloUnfurledLinkText`: a message that is only a link shows only its preview.
        static Element removeSoloUnfurledLinkText(Element content, Context ctx) {
            var unfurled = new ArrayList<Element>();
            for (Element node : attachmentNodes(content)) {
                if (OPENGRAPH_EMBED_CONTENT_TYPE.equals(node.attr("content-type"))) unfurled.add(node);
            }
            String soloUrl = null;
            if (unfurled.size() == 1) {
                OpengraphEmbed embed = opengraphEmbedFromNode(unfurled.getFirst(), ctx);
                soloUrl = embed == null ? null : embed.href();
            }
            String plain = toPlainText(content, ctx);
            if (!java.util.Objects.equals(normalizeTweetUrl(soloUrl), normalizeTweetUrl(plain))) return content;
            boolean trixBody = !content.getElementsByTag("div").isEmpty();
            if (trixBody) {
                String unfurl = Html.outerHtml(unfurled.getFirst());
                for (Element div : new ArrayList<>(content.getElementsByTag("div"))) {
                    if (div.parent() != null) setInnerHtml(div, unfurl);
                }
            } else {
                for (Element p : new ArrayList<>(content.getElementsByTag("p"))) {
                    if (p.getElementsByTag(ATTACHMENT_TAG).isEmpty()) p.remove();
                }
            }
            return content;
        }

        private static String normalizeTweetUrl(String url) {
            if (url == null) return null;
            boolean twitter = !Html.isBlank(url) && (Html.strip(url).contains("x.com") || Html.strip(url).contains("twitter.com"));
            if (!twitter) return url;
            try {
                URI uri = new URI(url);
                String host = uri.getHost();
                if (host != null && host.equalsIgnoreCase("x.com")) host = "twitter.com";
                return new URI(uri.getScheme(), uri.getUserInfo(), host, uri.getPort(), uri.getPath(), null, uri.getFragment()).toString();
            } catch (Exception e) {
                return url;
            }
        }

        /// `ContentFilters::SanitizeTags`: disallowed elements go with their contents.
        static void sanitizeTags(Element content) {
            for (Element e : new ArrayList<>(content.getAllElements())) {
                if (e != content && e.parent() != null && !Sanitizer.SANITIZE_TAGS_ALLOWED.contains(e.normalName())) e.remove();
            }
        }
    }
}
