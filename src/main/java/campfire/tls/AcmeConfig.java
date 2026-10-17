package campfire.tls;

import io.smallrye.config.ConfigMapping;
import java.net.IDN;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;

/// Thruster's TLS settings, bound in `application.properties` to the variables Thruster reads,
/// `THRUSTER_`-prefixed forms first.
@ConfigMapping(prefix = "campfire.tls")
@NullMarked
public interface AcmeConfig {
    /// `TLS_DOMAIN`, comma-separated. Unset, the app serves plain HTTP only.
    Optional<List<String>> domain();

    /// `ACME_DIRECTORY`. Besides an `https:` URL, it takes acme4j's `acme:` forms, such as
    /// `acme://pebble/<host>:14000` for the Pebble test CA.
    String acmeDirectory();

    /// `EAB_KID` and `EAB_HMAC_KEY` (unpadded URL-safe base64), for a CA that wants External
    /// Account Binding.
    Optional<String> eabKid();

    Optional<String> eabHmacKey();

    /// `STORAGE_PATH`: autocert's certificate cache, `storage/thruster` unless set.
    String storagePath();

    default List<String> domains() {
        return normalize(domain().orElse(List.of()));
    }

    /// The names as autocert's `HostWhitelist` keeps them: trimmed, in ASCII and lowercased.
    /// Names IDNA rejects are dropped.
    static List<String> normalize(List<String> names) {
        var domains = new ArrayList<String>();
        for (String name : names) {
            String trimmed = name.strip();
            if (trimmed.isEmpty()) continue;
            try {
                String ascii = IDN.toASCII(trimmed, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
                if (!domains.contains(ascii)) domains.add(ascii);
            } catch (IllegalArgumentException e) {
                // Not a host name.
            }
        }
        return List.copyOf(domains);
    }
}
