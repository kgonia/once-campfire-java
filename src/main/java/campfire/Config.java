package campfire;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.nio.file.Path;
import java.util.Optional;

/// Settings, bound in `application.properties` to the environment variables the Rails image reads
/// (`SECRET_KEY_BASE`, `DISABLE_SSL`, `VAPID_*`, `APP_VERSION`...), so the same `docker run` line
/// works for both images.
@ConfigMapping(prefix = "campfire")
public interface Config {
    /// Holds `db/production.sqlite3`, `files/` (Active Storage's disk service) and, when no secret is
    /// configured, the generated `secret_key_base`.
    @WithName("storage")
    @WithDefault("/rails/storage")
    String storageRoot();

    /// Unset means a secret generated once and kept in storage (see `Infrastructure`).
    Optional<String> secretKeyBase();

    /// Set (to anything) for asset builds, which get a throwaway secret instead.
    Optional<String> secretKeyBaseDummy();

    /// Without it every request is treated as HTTPS (`assume_ssl`): secure cookies, HSTS, https URLs.
    /// Read as Rails reads `ENV["DISABLE_SSL"].blank?`: any non-blank value, `false` included, turns it on.
    @WithName("disable-ssl")
    Optional<String> disableSslSetting();

    Optional<String> vapidPublicKey();

    Optional<String> vapidPrivateKey();

    @WithDefault("mailto:support@37signals.com")
    String vapidSubject();

    /// Sent as `X-Version` and shown in the account settings.
    @WithDefault("0")
    String appVersion();

    Optional<String> gitRevision();

    /// Memory for whole cached pages; 0 turns the response cache off.
    @WithDefault("64")
    long responseCacheMb();

    /// Largest accepted request body, uploads included.
    @WithDefault("16777216")
    long maxRequestBody();

    default boolean disableSsl() {
        return disableSslSetting().filter(value -> !value.isBlank()).isPresent();
    }

    default Path storage() {
        return Path.of(storageRoot());
    }

    default Path databasePath() {
        return storage().resolve("db").resolve("production.sqlite3");
    }

    default Path filesPath() {
        return storage().resolve("files");
    }

    default long responseCacheBytes() {
        return responseCacheMb() * 1024 * 1024;
    }
}
