package campfire.storage;

import campfire.storage.Blob;
import campfire.rails.MessageException;
import campfire.rails.MessageVerifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/// Active Storage route helpers (`activestorage/config/routes.rb`) and blob signed ids.
///
/// `rails_blob_path` and `url_for(representation)` resolve through
/// `resolve_model_to_route = :rails_storage_redirect`. `urls_expire_in` is unset in Campfire, so
/// signed blob ids never expire.
public final class Urls {
    private Urls() {}

    public static final String PREFIX = "/rails/active_storage";

    /// `blob.signed_id`: the Active Storage verifier with purpose "blob_id". `ActiveStorage::Blob`
    /// overrides both the signed-id verifier and `combine_signed_id_purposes`, so this is *not*
    /// the Active Record `signed_id` scheme.
    public static String signedBlobId(MessageVerifier verifier, long blobId) {
        return verifier.generateRaw(Long.toString(blobId), "blob_id", null);
    }

    /// The id from `ActiveStorage::Blob.find_signed`'s verification, or null.
    public static Long verifySignedBlobId(MessageVerifier verifier, String signedId, Instant now) {
        if (signedId == null) return null;
        try {
            return switch (verifier.verify(signedId, "blob_id", now)) {
                case Long l -> l;
                case Integer i -> (long) i;
                case null, default -> null;
            };
        } catch (MessageException e) {
            return null;
        }
    }

    /// `rails_blob_path(blob, disposition:)`: `/rails/active_storage/blobs/redirect/:signed_id/*filename`.
    public static String blobRedirectPath(MessageVerifier verifier, Blob blob, String disposition) {
        return blobPath("redirect", verifier, blob, disposition);
    }

    /// `rails_storage_proxy_path(blob)`: `/rails/active_storage/blobs/proxy/:signed_id/*filename`.
    public static String blobProxyPath(MessageVerifier verifier, Blob blob, String disposition) {
        return blobPath("proxy", verifier, blob, disposition);
    }

    /// `url_for(variant)`/`url_for(preview)`:
    /// `/rails/active_storage/representations/redirect/:signed_blob_id/:variation_key/*filename`.
    /// `blob` is the *original* blob (the video for previews); `variation` is exactly the one the
    /// representation holds (defaulted for variants, as given for previews).
    public static String representationRedirectPath(MessageVerifier verifier, Blob blob, Variation variation) {
        return representationPath("redirect", verifier, blob, variation);
    }

    public static String representationProxyPath(MessageVerifier verifier, Blob blob, Variation variation) {
        return representationPath("proxy", verifier, blob, variation);
    }

    private static String blobPath(String kind, MessageVerifier verifier, Blob blob, String disposition) {
        String path = PREFIX + "/blobs/" + kind + "/" + escapeSegment(signedBlobId(verifier, blob.id())) + "/"
            + escapePath(new Filename(blob.filename()).sanitized());
        // `Hash#to_query`, which escapes with `CGI.escape`.
        return disposition == null ? path : path + "?disposition=" + cgiEscape(disposition);
    }

    private static String representationPath(String kind, MessageVerifier verifier, Blob blob, Variation variation) {
        return PREFIX + "/representations/" + kind + "/" + escapeSegment(signedBlobId(verifier, blob.id())) + "/"
            + escapeSegment(variation.key(verifier)) + "/" + escapePath(new Filename(blob.filename()).sanitized());
    }

    /// `Journey::Router::Utils.escape_path`: keeps unreserved, sub-delims, ":", "@" and "/".
    public static String escapePath(String s) {
        return percentEscape(s, "-._~!$&'()*+,;=:@/");
    }

    /// `Journey::Router::Utils.escape_segment`: like `escape_path`, but "/" is escaped too.
    public static String escapeSegment(String s) {
        return percentEscape(s, "-._~!$&'()*+,;=:@");
    }

    /// `CGI.escape`: `A-Za-z0-9_.-~` stay, space is `+`.
    public static String cgiEscape(String s) {
        var out = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (alnum(c) || c == '_' || c == '.' || c == '-' || c == '~') out.append((char) c);
            else if (c == ' ') out.append('+');
            else hex(out, c);
        }
        return out.toString();
    }

    private static String percentEscape(String s, String keep) {
        var out = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (alnum(c) || (c < 128 && keep.indexOf(c) >= 0)) out.append((char) c);
            else hex(out, c);
        }
        return out.toString();
    }

    private static boolean alnum(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static void hex(StringBuilder out, int c) {
        out.append('%').append("0123456789ABCDEF".charAt(c >> 4)).append("0123456789ABCDEF".charAt(c & 0xf));
    }
}
