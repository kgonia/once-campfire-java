package campfire.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CertificateCacheTest {
    /// `openssl ecparam -name prime256v1 -genkey -noout`: the form autocert writes.
    private static final String SEC1 = """
        -----BEGIN EC PRIVATE KEY-----
        MHcCAQEEIG5OGupNC0FxH3AXSkAGwIJDK3cGjGtRRWYgHs4ovnTzoAoGCCqGSM49
        AwEHoUQDQgAEx4Ph0lvWp/mtK6ehtSouzqyPEbXzgtcp59tMiQeiHuF+lPGyklEW
        Ot6HbebraKMCA2Nm3v6HWJwkCMUTklFv5Q==
        -----END EC PRIVATE KEY-----
        """;

    @TempDir
    Path dir;

    @Test
    void certificatesRoundTripInAutocertsLayout() throws Exception {
        var cache = new CertificateCache(dir.resolve("thruster"));
        var issued = Certificates.placeholder("chat.example.com");
        KeyPair key = new KeyPair(issued.chain().getFirst().getPublicKey(), issued.key());
        cache.putCertificate("chat.example.com", key, issued.chain());

        String pem = Files.readString(dir.resolve("thruster/chat.example.com"));
        assertTrue(pem.startsWith("-----BEGIN EC PRIVATE KEY-----\n"), pem);
        assertTrue(pem.contains("-----END EC PRIVATE KEY-----\n-----BEGIN CERTIFICATE-----\n"), pem);
        assertFalse(pem.contains("\r"));
        var cached = cache.certificate("chat.example.com").orElseThrow();
        assertEquals(issued.chain(), cached.chain());
        assertEquals(((ECPrivateKey) issued.key()).getS(), ((ECPrivateKey) cached.key()).getS());

        // Not the name it covers.
        Files.copy(dir.resolve("thruster/chat.example.com"), dir.resolve("thruster/other.example.com"));
        assertTrue(cache.certificate("other.example.com").isEmpty());
        // A key that isn't the certificate's.
        var other = Certificates.placeholder("chat.example.com");
        cache.putCertificate("chat.example.com", new KeyPair(other.chain().getFirst().getPublicKey(), other.key()), issued.chain());
        assertTrue(cache.certificate("chat.example.com").isEmpty());
        assertTrue(cache.certificate("missing.example.com").isEmpty());
    }

    @Test
    void wildcardsCoverOneLabel() throws Exception {
        var certificate = Certificates.placeholder("*.example.com").chain().getFirst();
        assertTrue(CertificateCache.covers(certificate, "chat.example.com"));
        assertFalse(CertificateCache.covers(certificate, "a.chat.example.com"));
        assertFalse(CertificateCache.covers(certificate, "example.com"));
    }

    /// Thruster's account key reads, and writes back byte for byte, so either can go on using it.
    @Test
    void accountKeysStayReadableByGo() throws Exception {
        Files.createDirectories(dir.resolve("thruster"));
        Files.writeString(dir.resolve("thruster/" + CertificateCache.ACCOUNT_KEY), SEC1, StandardCharsets.US_ASCII);
        KeyPair key = new CertificateCache(dir.resolve("thruster")).accountKey().orElseThrow();
        new CertificateCache(dir.resolve("copy")).putAccountKey(key);
        assertEquals(SEC1, Files.readString(dir.resolve("copy/" + CertificateCache.ACCOUNT_KEY)));

        // And keys made here, as JDK keys, carry the curve and public key just the same.
        KeyPair fresh = Certificates.p256();
        new CertificateCache(dir.resolve("fresh")).putAccountKey(fresh);
        assertEquals(fresh.getPublic(), new CertificateCache(dir.resolve("fresh")).accountKey().orElseThrow().getPublic());
    }

    @Test
    void writesPrivately() throws Exception {
        var cache = new CertificateCache(dir.resolve("thruster"));
        cache.putAccountKey(Certificates.p256());
        if (!dir.getFileSystem().supportedFileAttributeViews().contains("posix")) return;
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("thruster"))));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("thruster/" + CertificateCache.ACCOUNT_KEY))));
        try (var files = Files.list(dir.resolve("thruster"))) {
            assertEquals(List.of(CertificateCache.ACCOUNT_KEY), files.map(f -> f.getFileName().toString()).toList());
        }
    }

    @Test
    void domainsAreNormalizedAsAutocertCompares() {
        assertEquals(List.of("chat.example.com", "xn--bcher-kva.example", "other.example.com"),
            AcmeConfig.normalize(List.of(" Chat.Example.COM", "", " ", "bücher.example", "other.example.com", "chat.example.com", "a/b.com")));
        assertEquals("chat.example.com", HttpPort.withoutPort("chat.example.com:80"));
        assertEquals("::1", HttpPort.withoutPort("[::1]:80"));
        assertEquals("::1", HttpPort.withoutPort("::1"));
    }
}
