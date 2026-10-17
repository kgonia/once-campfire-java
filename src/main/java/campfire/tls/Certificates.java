package campfire.tls;

import io.quarkus.logging.Log;
import io.quarkus.tls.CertificateUpdatedEvent;
import io.quarkus.tls.KeyStoreAndKeyCertOptions;
import io.quarkus.tls.KeyStoreProvider;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.quarkus.vertx.http.HttpServerStart;
import io.smallrye.common.annotation.Identifier;
import io.vertx.core.Vertx;
import io.vertx.core.net.KeyCertOptions;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManagerFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Login;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;

/// The HTTPS port's certificates for `TLS_DOMAIN`, obtained and renewed over ACME as Thruster's
/// autocert does: an ECDSA P-256 certificate per name, answering HTTP-01 on the HTTP port,
/// renewed 30 days before it expires.
///
/// The TLS registry reads the key store from here (`quarkus.http.tls-configuration-name`) when
/// it starts and on every `reload()`; a {@link CertificateUpdatedEvent} then makes the HTTPS
/// server take the new one without a restart. Without `TLS_DOMAIN` there's no key store, and
/// Quarkus opens no HTTPS port.
///
/// Unlike autocert, which orders a certificate during the first handshake that needs one,
/// ordering starts once the HTTP port listens, and until a name has its certificate the port
/// serves a self-signed one in its place: the registry wants a key store before any request.
@ApplicationScoped
@Identifier(Certificates.TLS_CONFIGURATION)
@NullMarked
public class Certificates implements KeyStoreProvider {
    static final String TLS_CONFIGURATION = "campfire";
    /// `autocert.Manager.RenewBefore`'s default, and its `renewJitter`.
    private static final Duration RENEW_BEFORE = Duration.ofDays(30);
    private static final Duration RENEW_JITTER = Duration.ofHours(1);
    private static final Duration ORDER_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration FIRST_RETRY = Duration.ofMinutes(1);
    private static final Duration LAST_RETRY = Duration.ofHours(1);
    private static final char[] NO_PASSWORD = new char[0];

    @Inject
    AcmeConfig config;
    /// Looked up when needed: the registry's bean is made from what it reads from here.
    @Inject
    Instance<TlsConfigurationRegistry> registry;
    @Inject
    Event<CertificateUpdatedEvent> updated;

    /// What the HTTPS port serves, by name.
    private final Map<String, CertificateCache.Entry> served = new ConcurrentHashMap<>();
    /// HTTP-01 key authorizations by token, while their orders are open.
    private final Map<String, String> challenges = new ConcurrentHashMap<>();
    private final ExecutorService renewals = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tls-certificate-", 0).factory());
    private @Nullable Login login;

    @Override
    public @Nullable KeyStoreAndKeyCertOptions getKeyStore(Vertx vertx) {
        List<String> domains = config.domains();
        if (domains.isEmpty()) return null;
        try {
            var store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            for (String domain : domains) {
                var entry = served.computeIfAbsent(domain, this::cachedOrPlaceholder);
                store.setKeyEntry(domain, entry.key(), NO_PASSWORD, entry.chain().toArray(X509Certificate[]::new));
            }
            // The PKIX key manager picks the certificate for the handshake's server name itself, so
            // no SNI setting is needed, and none is lost when a reload passes new options.
            var keyManagers = KeyManagerFactory.getInstance("PKIX");
            keyManagers.init(store, NO_PASSWORD);
            return new KeyStoreAndKeyCertOptions(store, KeyCertOptions.wrap(keyManagers));
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("cannot build the TLS key store", e);
        }
    }

    /// The HTTP-01 response for a challenge token, while its order is open.
    @Nullable String keyAuthorization(String token) {
        return challenges.get(token);
    }

    void start(@ObservesAsync HttpServerStart started) {
        for (String domain : config.domains()) renewals.execute(() -> keepCurrent(domain));
    }

    @PreDestroy
    void stop() {
        renewals.shutdownNow();
    }

    /// autocert's `domainRenewal`: renews `RENEW_BEFORE`, less up to `RENEW_JITTER`, ahead of
    /// expiry. A placeholder expires within a day, so its name is ordered right away. After a
    /// failure it tries again in a minute, doubling up to an hour.
    private void keepCurrent(String domain) {
        Duration retry = FIRST_RETRY;
        try {
            while (true) {
                Instant renewAt = served.computeIfAbsent(domain, this::cachedOrPlaceholder).notAfter().minus(RENEW_BEFORE)
                    .minusMillis(ThreadLocalRandom.current().nextLong(RENEW_JITTER.toMillis()));
                Duration wait = Duration.between(Instant.now(), renewAt);
                if (wait.isPositive()) Thread.sleep(wait);
                try {
                    served.put(domain, order(domain));
                    Log.infof("TLS: obtained a certificate for %s", domain);
                    retry = FIRST_RETRY;
                    reload();
                } catch (AcmeException | IOException | RuntimeException e) {
                    Log.errorf(e, "TLS: obtaining a certificate for %s failed; trying again in %s", domain, retry);
                    Thread.sleep(retry);
                    retry = retry.multipliedBy(2).compareTo(LAST_RETRY) > 0 ? LAST_RETRY : retry.multipliedBy(2);
                }
            }
        } catch (InterruptedException e) {
            // Shutting down.
        }
    }

    private void reload() {
        TlsConfiguration tls = registry.get().get(TLS_CONFIGURATION).orElseThrow();
        if (tls.reload()) updated.fire(new CertificateUpdatedEvent(TLS_CONFIGURATION, tls));
    }

    /// autocert's `verifiedOrder`, then the certificate written to the cache.
    private CertificateCache.Entry order(String domain) throws AcmeException, IOException, InterruptedException {
        Order order = login().newOrder().domain(domain).create();
        var tokens = new ArrayList<String>();
        try {
            for (Authorization authorization : order.getAuthorizations()) {
                if (authorization.getStatus() == Status.VALID) continue;
                Http01Challenge challenge = authorization.findChallenge(Http01Challenge.class)
                    .orElseThrow(() -> new AcmeException("the CA offers no HTTP-01 challenge for " + domain));
                challenges.put(challenge.getToken(), challenge.getAuthorization());
                tokens.add(challenge.getToken());
                challenge.trigger();
                if (authorization.waitForCompletion(ORDER_TIMEOUT) != Status.VALID) {
                    throw new AcmeException("authorization for " + domain + " failed: " + challenge.getError().map(Object::toString).orElse("no reason given"));
                }
            }
        } finally {
            tokens.forEach(challenges::remove);
        }
        order.waitUntilReady(ORDER_TIMEOUT);
        KeyPair key = p256();
        order.execute(key);
        if (order.waitForCompletion(ORDER_TIMEOUT) != Status.VALID) {
            throw new AcmeException("order for " + domain + " failed: " + order.getError().map(Object::toString).orElse("no reason given"));
        }
        List<X509Certificate> chain = order.getCertificate().getCertificateChain();
        cache().putCertificate(domain, key, chain);
        return new CertificateCache.Entry(key.getPrivate(), chain);
    }

    /// The ACME account of the cached account key, or of a new one, cached before it's registered.
    private synchronized Login login() throws AcmeException, IOException {
        if (login != null) return login;
        KeyPair key = cache().accountKey().orElse(null);
        if (key == null) {
            key = p256();
            cache().putAccountKey(key);
        }
        var account = new AccountBuilder().agreeToTermsOfService().useKeyPair(key);
        var hmacKey = externalAccountKey();
        if (hmacKey != null) account.withKeyIdentifier(config.eabKid().orElseThrow(), hmacKey);
        return login = account.createLogin(new Session(config.acmeDirectory()));
    }

    /// Thruster's `externalAccountBinding`: both settings, and a key that decodes; else none.
    private @Nullable SecretKeySpec externalAccountKey() {
        if (config.eabKid().isEmpty() || config.eabHmacKey().isEmpty()) return null;
        try {
            return new SecretKeySpec(Base64.getUrlDecoder().decode(config.eabHmacKey().get()), "HmacSHA256");
        } catch (IllegalArgumentException e) {
            Log.errorf("TLS: EAB_HMAC_KEY isn't URL-safe base64; registering without External Account Binding");
            return null;
        }
    }

    private CertificateCache cache() {
        return new CertificateCache(Path.of(config.storagePath()));
    }

    private CertificateCache.Entry cachedOrPlaceholder(String domain) {
        try {
            return cache().certificate(domain).orElseGet(() -> placeholder(domain));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// An ECDSA P-256 key from the JDK's provider (acme4j's `KeyPairUtils` asks for Bouncy Castle's).
    static KeyPair p256() {
        try {
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /// A self-signed certificate valid for a day, until the CA's arrives.
    static CertificateCache.Entry placeholder(String domain) {
        KeyPair key = p256();
        var name = new X500Name("CN=" + domain);
        Instant now = Instant.now();
        try {
            var builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now.toEpochMilli()), Date.from(now.minus(Duration.ofHours(1))),
                Date.from(now.plus(Duration.ofDays(1))), name, key.getPublic())
                .addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName(GeneralName.dNSName, domain)));
            var certificate = new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(key.getPrivate())));
            return new CertificateCache.Entry(key.getPrivate(), List.of(certificate));
        } catch (IOException | GeneralSecurityException | OperatorCreationException e) {
            throw new IllegalStateException(e);
        }
    }
}
