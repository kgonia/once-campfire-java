package campfire.storage;

import campfire.db.Database;
import campfire.db.Times;
import campfire.web.RecordNotFound;
import io.vertx.core.json.Json;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// What Active Storage's endpoints do with blobs: find them by signed id, process representations,
/// sign disk URLs, and take direct uploads. Signed ids and keys that don't verify are "absent"
/// (the endpoints answer an empty 404); a valid signed id for a deleted blob is a missing record.
@ApplicationScoped
@NullMarked
public class ActiveStorageService {
    @Inject
    Storage storage;
    @Inject
    Database db;

    /// A direct upload's blob, as `blob.as_json` plus `signed_id` and where to PUT the bytes.
    public record DirectUpload(Map<String, Object> json) {}

    public Optional<Blob> blob(String signedId) {
        Long id = Urls.verifySignedBlobId(storage.verifier(), signedId, Instant.now());
        return id == null ? Optional.empty() : Optional.of(RecordNotFound.require(storage.find(id)));
    }

    /// The variant or preview image `variationKey` names, processed now if it doesn't exist yet.
    /// Without libvips a variable image stands for itself.
    public Optional<Blob> representation(Blob blob, String variationKey) {
        Variation variation = Variation.decode(storage.verifier(), variationKey, Instant.now());
        if (variation == null) return Optional.empty();
        try {
            return Optional.of(storage.processedRepresentation(blob, variation));
        } catch (StorageException.Unavailable e) {
            if (!storage.variable(blob)) throw e;
            return Optional.of(blob);
        }
    }

    /// A disk URL on `baseUrl` that serves the blob for five minutes.
    public String serviceUrl(String baseUrl, Blob blob, @Nullable String disposition) {
        return storage.serviceUrl(baseUrl, blob, disposition);
    }

    /// The blob's file, if it's on disk.
    public Optional<Path> file(Blob blob) {
        Path path = storage.pathFor(blob);
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    public Optional<Disk.Key> diskKey(String encodedKey) {
        return Optional.ofNullable(Disk.decodeVerifiedKey(storage.verifier(), encodedKey, Instant.now()));
    }

    public Path pathFor(String key) {
        return storage.pathFor(key);
    }

    public Optional<Disk.Token> uploadToken(String encodedToken) {
        return Optional.ofNullable(Disk.decodeVerifiedToken(storage.verifier(), encodedToken, Instant.now()));
    }

    /// Stores a direct upload's bytes; false when they don't match the checksum it was signed for.
    public boolean upload(Disk.Token token, byte[] body) {
        try {
            storage.disk().upload(token.key(), new ByteArrayInputStream(body), token.checksum());
            return true;
        } catch (Disk.IntegrityException e) {
            return false;
        }
    }

    public long maxDirectUploadBytes() {
        return storage.maxDirectUploadBytes();
    }

    /// Creates the blob row for a direct upload; the bytes follow in a PUT to the returned URL.
    /// `metadata` is stored as given, `{}` when absent.
    public DirectUpload createDirectUpload(String baseUrl, String filename, @Nullable String contentType, Map<?, ?> metadata, long byteSize,
                                           String checksum) {
        String key = storage.generateKey();
        Instant now = Times.now();
        Blob blob = db.write(tx -> Blob.find(tx, Blob.create(tx, key, filename, contentType, Json.encode(metadata), byteSize, checksum, now)));
        Map<String, Object> json = blob.asJson();
        json.put("signed_id", Urls.signedBlobId(storage.verifier(), blob.id()));
        var headers = new LinkedHashMap<String, @Nullable Object>();
        headers.put("Content-Type", contentType);
        var directUpload = new LinkedHashMap<String, Object>();
        directUpload.put("url", baseUrl + storage.disk().directUploadPath(storage.verifier(), key, now.plus(Storage.SERVICE_URLS_EXPIRE_IN),
            contentType, byteSize, checksum));
        directUpload.put("headers", headers);
        json.put("direct_upload", directUpload);
        return new DirectUpload(json);
    }
}
