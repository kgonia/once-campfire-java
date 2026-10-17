package campfire.integrations.opengraph;

import campfire.integrations.net.Deadline;
import campfire.integrations.net.Network;
import campfire.integrations.net.RubyUri;
import campfire.rails.Json;
import campfire.richtext.Html;
import campfire.richtext.Sanitizer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/// `Opengraph::Metadata` and its `Fetching` concern (reference/app/models/opengraph/metadata.rb,
/// reference/app/models/opengraph/metadata/fetching.rb).
public final class Metadata {
    private static final Logger LOG = Logger.getLogger(Metadata.class.getName());

    private static final List<String> TWITTER_HOSTS = List.of("twitter.com", "www.twitter.com", "x.com", "www.x.com");
    private static final String FX_TWITTER_HOST = "fxtwitter.com";
    private static final List<String> ALLOWED_IMAGE_CONTENT_TYPES = List.of("image/jpeg", "image/png", "image/gif", "image/webp");

    /// Where Rails raises (a 500 from the controller), with the Ruby exception class.
    public static final class RaisedException extends Exception {
        public RaisedException(String rubyClass) {
            super(rubyClass, null, false, false);
        }

        public String rubyClass() {
            return getMessage();
        }
    }

    /// The model's attributes as instance variables, in the order they were first assigned
    /// (which is the order `render json:` emits them). Values may be null.
    private final LinkedHashMap<String, String> attributes;
    private final Fetch.Context context;

    private Metadata(LinkedHashMap<String, String> attributes, Fetch.Context context) {
        this.attributes = attributes;
        this.context = context;
    }

    /// `Metadata.from_url(url)`
    static Metadata fromUrl(Network net, String url, Deadline deadline) throws RaisedException {
        var context = new Fetch.Context(net, deadline);
        byte[] body = fetchDocument(context, url);
        Map<String, String> found = Document.opengraphAttributes(body);
        String canonicalUrl = validCanonicalUrl(context, found.get("url"), url);
        String image = validImageContentType(context, found.get("image"));
        var attributes = new LinkedHashMap<>(found);
        attributes.put("url", canonicalUrl);
        attributes.put("image", image);
        return new Metadata(attributes, context);
    }

    /// The `title`, `url`, `image` or `description`.
    public String get(String key) {
        return attributes.get(key);
    }

    /// `valid?`: sanitizes the title and description first (`before_validation`), then checks
    /// presence and, when there's an image, that it's a valid location.
    boolean validate() {
        for (String key : List.of("title", "description")) {
            String value = attributes.get(key);
            attributes.put(key, value == null ? null : sanitize(stripTags(value)));
        }
        boolean valid = present("title") && present("url") && present("description");
        String image = attributes.get("image");
        if (image != null && !Document.isBlank(image)) valid &= new Location(context, image).isValid();
        return valid;
    }

    private boolean present(String key) {
        return !Document.isBlank(attributes.get(key));
    }

    /// `render json: opengraph` after `valid?`: `instance_values`, which by then include the
    /// validation context and the (empty) errors, encoded by `ActiveSupport::JSON`.
    public String toJson() {
        var values = new LinkedHashMap<String, Object>(attributes);
        values.put("context_for_validation", Collections.singletonMap("context", null));
        values.put("errors", Map.of());
        return Json.encode(values);
    }

    /// `fetch_document(untrusted_url)`: tweets are read through fxtwitter.com. A tweet whose
    /// fxtwitter page can't be read raises (`nil.force_encoding`), as does a URL that `URI.parse`
    /// rejects with `URI::InvalidComponentError`.
    private static byte[] fetchDocument(Fetch.Context context, String url) throws RaisedException {
        if (tweetUrl(url)) {
            byte[] html = new Location(context, replaceTwitterDomain(url)).readHtml();
            if (html == null) throw new RaisedException("NoMethodError");
            return html;
        }
        return new Location(context, url).readHtml();
    }

    /// `tweet_url?`
    private static boolean tweetUrl(String url) throws RaisedException {
        try {
            var uri = RubyUri.parse(url);
            return uri.host() != null && TWITTER_HOSTS.contains(uri.host()) && !Document.isBlank(uri.path()) && !"/".equals(uri.path());
        } catch (RubyUri.InvalidUriException e) {
            if (e.invalidComponent()) throw new RaisedException(e.className());
            return false;
        }
    }

    /// `replace_twitter_domain_for_opengraph_support`
    private static String replaceTwitterDomain(String url) {
        var uri = RubyUri.parseOrNull(url);
        if (uri == null) return null;
        if (uri.host() != null && TWITTER_HOSTS.contains(uri.host())) uri = uri.withHost(FX_TWITTER_HOST);
        return uri.toString();
    }

    /// `valid_canonical_url(url, fallback)`
    private static String validCanonicalUrl(Fetch.Context context, String url, String fallback) {
        return url != null && new Location(context, url).isValid() ? url : fallback;
    }

    /// `valid_image_content_type(image)`: kept only when a HEAD says it's a JPEG, PNG, GIF or WebP.
    private static String validImageContentType(Fetch.Context context, String image) {
        if (image == null || Document.isBlank(image)) return null;
        if (RubyUri.parseOrNull(image) == null) {
            LOG.warning("Failed to fetch image content tpye: " + image + " (bad URI(is not URI?): \"" + image + "\")");
            return null;
        }
        String contentType = new Location(context, image).fetchContentType();
        return contentType != null && ALLOWED_IMAGE_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT)) ? image : null;
    }

    /// `strip_tags` (Rails::HTML5::FullSanitizer): the text of the HTML5 fragment, serialized.
    static String stripTags(String html) {
        if (html.isEmpty()) return "";
        Element fragment = Html.parseFragment(html);
        var text = new StringBuilder();
        collectText(fragment, text);
        var out = new Element("div");
        if (!text.isEmpty()) out.appendChild(new TextNode(text.toString()));
        return Html.innerHtml(out);
    }

    private static void collectText(Node node, StringBuilder out) {
        for (Node child : node.childNodes()) {
            switch (child) {
                case TextNode t -> out.append(t.getWholeText());
                case DataNode d -> out.append(d.getWholeData());
                case Element e -> collectText(e, out);
                default -> {}
            }
        }
    }

    /// `sanitize` (Rails::HTML5::SafeListSanitizer with its default allowlist).
    static String sanitize(String html) {
        return Sanitizer.sanitize(html, Sanitizer.DEFAULTS);
    }
}
