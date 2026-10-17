package campfire;

import campfire.assets.Assets;
import campfire.db.Database;
import campfire.accounts.Account;
import campfire.storage.Attachment;
import campfire.rails.Secrets;
import campfire.storage.Storage;
import campfire.sessions.SessionLookup;
import jakarta.inject.Inject;
import jakarta.enterprise.context.ApplicationScoped;

/// The services views and presenters reach through one handle; resources inject what they use.
@ApplicationScoped
public class App {
    private final Config config;
    private final Secrets secrets;
    private final Database db;
    private final Assets assets;
    private final Storage storage;
    private final SessionLookup sessions;

    /// The account, whether its logo is attached, and the data version they were read at.
    public record AccountState(Account account, boolean hasLogo) {}

    private record Versioned(long version, AccountState state) {}

    private volatile Versioned account = new Versioned(-1, null);

    @Inject
    public App(Config config, Secrets secrets, Database db, Assets assets, Storage storage, SessionLookup sessions) {
        this.config = config;
        this.secrets = secrets;
        this.db = db;
        this.assets = assets;
        this.storage = storage;
        this.sessions = sessions;
    }

    public Config config() {
        return config;
    }

    public Secrets secrets() {
        return secrets;
    }

    public Database db() {
        return db;
    }

    public Assets assets() {
        return assets;
    }

    public Storage storage() {
        return storage;
    }

    public SessionLookup sessions() {
        return sessions;
    }

    /// Read at most once per data version: every page needs it and it changes rarely.
    public AccountState account() {
        long version = db.dataVersion();
        Versioned current = account;
        if (current.version() == version) return current.state();
        AccountState state = db.read(conn -> {
            Account a = Account.first(conn);
            return new AccountState(a, a != null && Attachment.find(conn, "Account", a.id(), "logo") != null);
        });
        account = new Versioned(version, state);
        return state;
    }
}
