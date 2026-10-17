package campfire.storage;

import campfire.db.Database.Tx;
import campfire.db.Times;
import campfire.web.Params;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// What a form's file field does to a record's single attachment (a user's avatar, the account's
/// logo). A new file is copied into storage by {@link #stage} before the record's transaction, so
/// the transaction only writes rows; {@link Staged#apply} then replaces or removes the attachment
/// and touches the record. Closing the staging deletes the copy unless the transaction committed.
@NullMarked
public sealed interface AttachmentChange {
    /// The field wasn't sent.
    record Unchanged() implements AttachmentChange {}

    /// Sent empty: the attachment is removed.
    record Delete() implements AttachmentChange {}

    record Upload(Params.Upload file) implements AttachmentChange {}

    /// Anything else, which Rails can't turn into a blob (a 500 when applied).
    record Invalid() implements AttachmentChange {}

    /// `key` of a parameter hash; a null or "" value deletes.
    static AttachmentChange of(Map<String, ?> params, String key) {
        if (!params.containsKey(key)) return new Unchanged();
        return switch (params.get(key)) {
            case null -> new Delete();
            case String s when s.isEmpty() -> new Delete();
            case Params.Upload file -> new Upload(file);
            default -> new Invalid();
        };
    }

    default Staged stage(Storage storage) {
        return new Staged(storage, this, this instanceof Upload(Params.Upload file) ? storage.stage(file.path(), file.filename(), file.contentType()) : null);
    }

    final class Staged implements AutoCloseable {
        private final Storage storage;
        private final AttachmentChange change;
        private final Storage.@Nullable Staged file;

        private Staged(Storage storage, AttachmentChange change, Storage.@Nullable Staged file) {
            this.storage = storage;
            this.change = change;
            this.file = file;
        }

        /// Within the record's transaction. `table` is the record's table, touched for every change
        /// to its attachment. A new blob is analyzed once the transaction commits.
        public void apply(Tx tx, String recordType, String table, long recordId, String name) {
            switch (change) {
                case Unchanged u -> {}
                case Delete d -> {
                    if (Attachment.find(tx, recordType, recordId, name) == null) return;
                    storage.detachAndPurgeLater(tx, recordType, recordId, name);
                    touch(tx, table, recordId);
                }
                case Upload u -> {
                    Blob blob = storage.insert(tx, java.util.Objects.requireNonNull(file));
                    storage.attach(tx, recordType, recordId, name, blob);
                    touch(tx, table, recordId);
                }
                case Invalid i -> throw new IllegalStateException("Could not find or build blob: expected attachable");
            }
        }

        private static void touch(Tx tx, String table, long id) {
            tx.update("UPDATE \"" + table + "\" SET \"updated_at\" = ? WHERE \"" + table + "\".\"id\" = ?", Times.now(), id);
        }

        @Override
        public void close() {
            if (file != null) file.close();
        }
    }
}
