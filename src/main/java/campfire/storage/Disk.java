package campfire.storage;

import campfire.rails.HttpFormats;
import campfire.rails.MessageException;
import campfire.rails.MessageVerifier;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/// `ActiveStorage::Service::DiskService`: files at `<root>/<key[0,2]>/<key[2,2]>/<key>`, signed disk
/// URLs (`/rails/active_storage/disk/:encoded_key/*filename`) and direct-upload tokens
/// (`PUT /rails/active_storage/disk/:encoded_token`).
public final class Disk {
    private final Path root;
    private final String name;

    /// The payload of a disk URL's `encoded_key` (purpose "blob_key").
    public record Key(String key, String disposition, String contentType, String serviceName) {}

    /// The payload of a direct-upload `encoded_token` (purpose "blob_token").
    public record Token(String key, String contentType, long contentLength, String checksum, String serviceName) {}

    /// Campfire's `local` service: `root: Rails.root.join("storage", "files")` (config/storage.yml).
    public Disk(Path root, String name) {
        this.root = root;
        this.name = name;
    }

    /// `path_for(key)`.
    public Path pathFor(String key) {
        String a = key.length() >= 2 ? key.substring(0, 2) : key;
        String b = key.length() >= 4 ? key.substring(2, 4) : key.length() > 2 ? key.substring(2) : "";
        return root.resolve(a).resolve(b).resolve(key);
    }

    /// `upload(key, io, checksum:)`: write, then verify the MD5 and delete on mismatch.
    public void upload(String key, InputStream in, String checksum) {
        Path path = pathFor(key);
        try {
            Files.createDirectories(path.getParent());
            var digest = new DigestInputStream(in, md5());
            Files.copy(digest, path, StandardCopyOption.REPLACE_EXISTING);
            if (checksum != null && !checksum.equals(campfire.rails.Base64s.strictEncode(digest.getMessageDigest().digest()))) {
                delete(key);
                throw new IntegrityException();
            }
        } catch (IOException e) {
            delete(key);
            throw new UncheckedIOException(e);
        }
    }

    /// The upload's bytes don't match its checksum (`ActiveStorage::IntegrityError`).
    public static final class IntegrityException extends StorageException {
        IntegrityException() {
            super("checksum mismatch");
        }
    }

    public void delete(String key) {
        try {
            Files.deleteIfExists(pathFor(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// `delete_prefixed("variants/#{key}/")`: legacy untracked variants of an image.
    public void deleteLegacyVariants(String key) {
        // `path_for("variants/#{key}/")` puts the folder under `va/ri/`.
        Path dir = root.resolve("va").resolve("ri").resolve("variants").resolve(key);
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// The path of `service.url(key, expires_in:, filename:, content_type:, disposition:)`; the
    /// caller prefixes the request's protocol and host (`ActiveStorage::Current.url_options`).
    public String urlPath(MessageVerifier verifier, String key, Instant expiresAt, Filename filename, String contentType, String disposition) {
        String sanitized = filename.sanitized();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("key", key);
        payload.put("disposition", contentDispositionWith(disposition, sanitized));
        payload.put("content_type", contentType);
        payload.put("service_name", name);
        String encodedKey = verifier.generateRaw(io.vertx.core.json.Json.encode(payload), "blob_key", expiresAt);
        return Urls.PREFIX + "/disk/" + Urls.escapeSegment(encodedKey) + "/" + Urls.escapePath(sanitized);
    }

    /// The path of `url_for_direct_upload(key, expires_in:, content_type:, content_length:, checksum:)`.
    public String directUploadPath(MessageVerifier verifier, String key, Instant expiresAt, String contentType, long contentLength,
                                   String checksum) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("key", key);
        payload.put("content_type", contentType);
        payload.put("content_length", contentLength);
        payload.put("checksum", checksum);
        payload.put("service_name", name);
        String token = verifier.generateRaw(io.vertx.core.json.Json.encode(payload), "blob_token", expiresAt);
        return Urls.PREFIX + "/disk/" + Urls.escapeSegment(token);
    }

    /// `ActiveStorage::Service#content_disposition_with`: anything but "attachment" is "inline".
    public static String contentDispositionWith(String disposition, String sanitizedFilename) {
        return HttpFormats.contentDisposition("attachment".equals(disposition) ? "attachment" : "inline", sanitizedFilename);
    }

    /// `DiskController#decode_verified_key`, or null.
    public static Key decodeVerifiedKey(MessageVerifier verifier, String encodedKey, Instant now) {
        if (!(verified(verifier, encodedKey, "blob_key", now) instanceof Map<?, ?> data)) return null;
        if (!(data.get("key") instanceof String key) || !(data.get("disposition") instanceof String disposition)) return null;
        return new Key(key, disposition, data.get("content_type") instanceof String t ? t : null,
            data.get("service_name") instanceof String s ? s : null);
    }

    /// `DiskController#decode_verified_token`, or null.
    public static Token decodeVerifiedToken(MessageVerifier verifier, String encodedToken, Instant now) {
        if (!(verified(verifier, encodedToken, "blob_token", now) instanceof Map<?, ?> data)) return null;
        if (!(data.get("key") instanceof String key) || !(data.get("content_length") instanceof Number length)) return null;
        return new Token(key, data.get("content_type") instanceof String t ? t : null, length.longValue(),
            data.get("checksum") instanceof String c ? c : null, data.get("service_name") instanceof String s ? s : null);
    }

    private static Object verified(MessageVerifier verifier, String message, String purpose, Instant now) {
        if (message == null) return null;
        try {
            return verifier.verify(message, purpose, now);
        } catch (MessageException e) {
            return null;
        }
    }

    static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /// `OpenSSL::Digest::MD5.base64digest` of a file.
    public static String checksum(Path file) {
        try (var in = new DigestInputStream(Files.newInputStream(file), md5())) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return campfire.rails.Base64s.strictEncode(in.getMessageDigest().digest());
        } catch (NoSuchFileException e) {
            throw new StorageException("file not found: " + file.getFileName(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
