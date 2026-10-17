package campfire.tls;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// autocert's `DirCache`, where Thruster keeps its certificates, so an install that ran Thruster
/// keeps its certificates and ACME account. `<domain>` holds the private key (SEC1
/// `EC PRIVATE KEY`) followed by the PEM chain; `acme_account+key` holds the account's key.
/// Files written here are readable by Thruster too.
@NullMarked
final class CertificateCache {
    static final String ACCOUNT_KEY = "acme_account+key";

    /// A key and the chain it signs for, leaf first.
    record Entry(PrivateKey key, List<X509Certificate> chain) {
        Instant notAfter() {
            return chain.getFirst().getNotAfter().toInstant();
        }
    }

    private final Path dir;

    CertificateCache(Path dir) {
        this.dir = dir;
    }

    /// autocert's `cacheGet`: the cached certificate for `domain` if it's current, covers the
    /// domain and matches its key; anything else is ignored and later replaced.
    Optional<Entry> certificate(String domain) throws IOException {
        String pem = read(domain);
        if (pem == null) return Optional.empty();
        try {
            Entry entry = parse(pem);
            X509Certificate leaf = entry.chain().getFirst();
            leaf.checkValidity();
            return covers(leaf, domain) && keysMatch(entry.key(), leaf) ? Optional.of(entry) : Optional.empty();
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            return Optional.empty();
        }
    }

    void putCertificate(String domain, KeyPair key, List<X509Certificate> chain) throws IOException {
        var pem = new StringBuilder(sec1(key));
        for (X509Certificate certificate : chain) {
            try {
                pem.append(pemBlock("CERTIFICATE", certificate.getEncoded()));
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
        }
        write(domain, pem.toString());
    }

    Optional<KeyPair> accountKey() throws IOException {
        String pem = read(ACCOUNT_KEY);
        if (pem == null) return Optional.empty();
        try (var parser = new PEMParser(new StringReader(pem))) {
            return parser.readObject() instanceof PEMKeyPair pair ? Optional.of(new JcaPEMKeyConverter().getKeyPair(pair)) : Optional.empty();
        }
    }

    void putAccountKey(KeyPair key) throws IOException {
        write(ACCOUNT_KEY, sec1(key));
    }

    /// `cacheGet`'s layout: the key first, SEC1 or PKCS#8 as `parsePrivateKey` takes it, then only
    /// certificates.
    static Entry parse(String pem) throws IOException, CertificateException {
        var converter = new JcaPEMKeyConverter();
        try (var parser = new PEMParser(new StringReader(pem))) {
            PrivateKey key = switch (parser.readObject()) {
                case PEMKeyPair pair -> converter.getPrivateKey(pair.getPrivateKeyInfo());
                case PrivateKeyInfo info -> converter.getPrivateKey(info);
                case null, default -> throw new IOException("a certificate cache file starts with its private key");
            };
            var chain = new ArrayList<X509Certificate>();
            for (Object block = parser.readObject(); block != null; block = parser.readObject()) {
                if (!(block instanceof X509CertificateHolder holder)) throw new IOException("only certificates follow the key");
                chain.add(new JcaX509CertificateConverter().getCertificate(holder));
            }
            if (chain.isEmpty()) throw new IOException("a certificate cache file holds a certificate");
            return new Entry(key, List.copyOf(chain));
        }
    }

    /// `x509.Certificate.VerifyHostname` for DNS names, a wildcard covering one label.
    static boolean covers(X509Certificate certificate, String domain) throws CertificateParsingException {
        var names = certificate.getSubjectAlternativeNames();
        if (names == null) return false;
        for (List<?> name : names) {
            if (!Integer.valueOf(2).equals(name.get(0)) || !(name.get(1) instanceof String dns)) continue;
            String pattern = dns.toLowerCase(Locale.ROOT);
            if (pattern.equals(domain)) return true;
            int dot = domain.indexOf('.');
            if (pattern.startsWith("*.") && dot > 0 && domain.substring(dot + 1).equals(pattern.substring(2))) return true;
        }
        return false;
    }

    /// Whether the certificate's public key verifies what the key signs, for EC and RSA keys alike.
    static boolean keysMatch(PrivateKey key, X509Certificate certificate) throws GeneralSecurityException {
        String algorithm = key.getAlgorithm().equals("EC") ? "SHA256withECDSA" : "SHA256withRSA";
        byte[] probe = "campfire".getBytes(StandardCharsets.US_ASCII);
        var signer = Signature.getInstance(algorithm);
        signer.initSign(key);
        signer.update(probe);
        byte[] signature = signer.sign();
        var verifier = Signature.getInstance(algorithm);
        verifier.initVerify(certificate.getPublicKey());
        verifier.update(probe);
        return verifier.verify(signature);
    }

    /// An EC key as autocert writes it (`x509.MarshalECPrivateKey`): SEC1 with the curve and the
    /// public key, which Go needs to read it back. JDK keys encode neither inside their PKCS#8.
    static String sec1(KeyPair key) throws IOException {
        var pkcs8 = PrivateKeyInfo.getInstance(key.getPrivate().getEncoded());
        var inner = ECPrivateKey.getInstance(pkcs8.parsePrivateKey());
        var publicKey = SubjectPublicKeyInfo.getInstance(key.getPublic().getEncoded()).getPublicKeyData();
        int bits = ((java.security.interfaces.ECPrivateKey) key.getPrivate()).getParams().getOrder().bitLength();
        var sec1 = new ECPrivateKey(bits, inner.getKey(), publicKey, pkcs8.getPrivateKeyAlgorithm().getParameters());
        return pemBlock("EC PRIVATE KEY", sec1.getEncoded());
    }

    /// Go's `pem.Encode`: 64-column lines ending in `\n` whatever the platform.
    private static String pemBlock(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private @Nullable String read(String name) throws IOException {
        try {
            return Files.readString(dir.resolve(name), StandardCharsets.US_ASCII);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    /// `DirCache.Put`: the directory 0700 and the file 0600, through a temporary file so a reader
    /// never sees half of one.
    private void write(String name, String content) throws IOException {
        boolean posix = dir.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (posix) Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        else Files.createDirectories(dir);
        Path temporary = posix
            ? Files.createTempFile(dir, name + ".", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            : Files.createTempFile(dir, name + ".", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.US_ASCII);
            Files.move(temporary, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
