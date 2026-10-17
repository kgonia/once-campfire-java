package campfire.integrations.opengraph;

import campfire.integrations.net.Network;
import campfire.integrations.net.Url;
import campfire.rails.Json;
import campfire.richtext.Html;
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
final class Metadata {
    private static final Logger LOG = Logger.getLogger(Metadata.class.getName());

    private static final List<String> TWITTER_HOSTS = List.of("twitter.com", "www.twitter.com", "x.com", "www.x.com");
    private static final String FX_TWITTER_HOST = "fxtwitter.com";
    private static final List<String> ALLOWED_IMAGE_CONTENT_TYPES = List.of("image/jpeg", "image/png", "image/gif", "image/webp");

    /// `title`, `url`, `image` and `description`, in the order `render json:` emits them. Values
    /// may be null.
    private final LinkedHashMap<String, String> attributes;
    private final Network net;

    private Metadata(LinkedHashMap<String, String> attributes, Network net) {
        this.attributes = attributes;
        this.net = net;
    }

    /// `Metadata.from_url(url)`
    static Metadata fromUrl(Network net, String url) {
        byte[] body = fetchDocument(net, url);
        Map<String, String> found = Document.opengraphAttributes(body);
        String canonicalUrl = validCanonicalUrl(net, found.get("url"), url);
        String image = validImageContentType(net, found.get("image"));
        var attributes = new LinkedHashMap<>(found);
        attributes.put("url", canonicalUrl);
        attributes.put("image", image);
        return new Metadata(attributes, net);
    }

    /// `valid?`: reduces the title and description to text first (`before_validation`), then
    /// checks presence and, when there's an image, that it's a valid location.
    boolean validate() {
        for (String key : List.of("title", "description")) {
            String value = attributes.get(key);
            attributes.put(key, value == null ? null : plainText(value));
        }
        boolean valid = present("title") && present("url") && present("description");
        String image = attributes.get("image");
        if (image != null && !Document.isBlank(image)) valid &= new Location(net, image).isValid();
        return valid;
    }

    private boolean present(String key) {
        return !Document.isBlank(attributes.get(key));
    }

    /// `render json: opengraph`, encoded by `ActiveSupport::JSON`.
    String toJson() {
        return Json.encode(attributes);
    }

    /// `fetch_document(untrusted_url)`: tweets are read through fxtwitter.com.
    private static byte[] fetchDocument(Network net, String url) {
        return new Location(net, tweetUrl(url) ? replaceTwitterDomain(url) : url).readHtml();
    }

    /// `tweet_url?`
    private static boolean tweetUrl(String url) {
        var uri = Url.parseOrNull(url);
        if (uri == null || uri.host() == null || !TWITTER_HOSTS.contains(uri.host())) return false;
        String path = uri.uri().getRawPath();
        return path != null && !Document.isBlank(path) && !path.equals("/");
    }

    /// `replace_twitter_domain_for_opengraph_support`, for a tweet URL.
    private static String replaceTwitterDomain(String url) {
        var fx = Url.parseOrNull(url).withHost(FX_TWITTER_HOST);
        return fx == null ? null : fx.toString();
    }

    /// `valid_canonical_url(url, fallback)`
    private static String validCanonicalUrl(Network net, String url, String fallback) {
        return url != null && new Location(net, url).isValid() ? url : fallback;
    }

    /// `valid_image_content_type(image)`: kept only when a HEAD says it's a JPEG, PNG, GIF or WebP.
    private static String validImageContentType(Network net, String image) {
        if (image == null || Document.isBlank(image)) return null;
        if (Url.parseOrNull(image) == null) {
            LOG.warning("Failed to fetch image content type: " + image + " (not a URL)");
            return null;
        }
        String contentType = new Location(net, image).fetchContentType();
        return contentType != null && ALLOWED_IMAGE_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT)) ? image : null;
    }

    /// The text of the HTML5 fragment, as `strip_tags` (Rails::HTML5::FullSanitizer) finds it.
    /// Rails sends it HTML-escaped (`sanitize` after `strip_tags`) and the composer escapes what
    /// it gets again, so a title "Tom & Jerry" showed as "Tom &amp; Jerry"; this is unescaped.
    static String plainText(String html) {
        var text = new StringBuilder();
        if (!html.isEmpty()) collectText(Html.parseFragment(html), text);
        return text.toString();
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
}
