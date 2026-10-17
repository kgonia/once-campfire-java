package campfire.integrations.opengraph;

import campfire.integrations.net.Guard;
import campfire.integrations.net.Network;
import campfire.integrations.net.Url;
import java.net.InetAddress;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/// `Opengraph::Location` (reference/app/models/opengraph/location.rb): a URL that is valid when
/// it parses as http(s) and its host resolves to a public address, which is memoized and pinned
/// for the fetch.
final class Location {
    private static final Logger LOG = Logger.getLogger(Location.class.getName());

    /// `FILES_AND_MEDIA_URL_REGEX`
    private static final Pattern FILES_AND_MEDIA_URL = Pattern.compile("\\bhttps?://\\S+\\.(?:zip|tar|tar\\.gz|tar\\.bz2|tar\\.xz|gz|bz2|rar|7z"
        + "|dmg|exe|msi|pkg|deb|iso|jpg|jpeg|png|gif|bmp|mp4|mov|avi|mkv|wmv|flv|heic|heif|mp3|wav|ogg|aac|wma|webm|ogv|mpg|mpeg)\\b");

    private final Network net;
    private final String url;
    private final Url parsedUrl;
    private boolean resolved;
    private InetAddress resolvedIp;

    /// `Location.new(url)`: `parsed_url` is `URI.parse(url) rescue nil`.
    Location(Network net, String url) {
        this.net = net;
        this.url = url;
        this.parsedUrl = Url.parseOrNull(url);
    }

    /// `valid?`: both validations run, so the host is resolved even for a non-http URL.
    boolean isValid() {
        boolean http = parsedUrl != null && parsedUrl.isHttp();
        boolean isPublic = resolvedIp() != null;
        return http && isPublic;
    }

    /// `resolved_ip`: `PrivateNetworkGuard.resolve(parsed_url.host) rescue nil`, memoized.
    InetAddress resolvedIp() {
        if (!resolved) {
            resolved = true;
            if (parsedUrl != null && parsedUrl.host() != null) {
                try {
                    resolvedIp = Guard.resolve(net.resolver(), parsedUrl.host());
                } catch (Guard.GuardException e) {
                    resolvedIp = null;
                }
            }
        }
        return resolvedIp;
    }

    /// `read_html`: nothing for invalid URLs or ones that look like files and media.
    byte[] readHtml() {
        if (!isValid() || FILES_AND_MEDIA_URL.matcher(url == null ? "" : url).find()) return null;
        try {
            return Fetch.fetchDocument(net, parsedUrl, resolvedIp());
        } catch (Fetch.FetchException e) {
            LOG.warning("Failed to fetch " + parsedUrl + " at " + resolvedIp().getHostAddress() + " (" + e.getMessage() + ")");
            return null;
        }
    }

    /// `fetch_content_type`
    String fetchContentType() {
        if (!isValid()) return null;
        try {
            return Fetch.fetchContentType(net, parsedUrl, resolvedIp());
        } catch (Fetch.FetchException e) {
            LOG.warning("Failed to fetch " + parsedUrl + " at " + resolvedIp().getHostAddress() + " (" + e.getMessage() + ")");
            return null;
        }
    }
}
