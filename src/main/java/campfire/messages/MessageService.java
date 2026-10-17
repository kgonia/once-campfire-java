package campfire.messages;

import campfire.cable.Broadcasts;
import campfire.db.Conn;
import campfire.db.Database;
import campfire.db.Times;
import campfire.richtext.RichText;
import campfire.rooms.Room;
import campfire.storage.Attachment;
import campfire.storage.Blob;
import campfire.storage.Storage;
import campfire.users.User;
import campfire.view.Safe;
import campfire.view.View;
import campfire.web.Params;
import campfire.web.RecordNotFound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Posting, editing and deleting messages, each change broadcast to the room. Broadcasts render
/// with a detached view (no viewer), so every member and the poster's own response share the
/// cached fragment.
@ApplicationScoped
@NullMarked
public class MessageService {
    @Inject
    Database db;
    @Inject
    Storage storage;
    @Inject
    Broadcasts broadcasts;
    @Inject
    MessageDelivery delivery;

    public record Posted(Message message, Safe html) {}

    /// A message editable in place, with its body as the editor loads it.
    public record Editing(MessageView message, String editableBody) {}

    /// A message in the room by its id parameter, or a 404.
    public Message find(Room room, String id) {
        Long messageId = Params.parseId(id);
        return RecordNotFound.require(messageId == null ? null : db.read(conn -> Message.findInRoom(conn, room.id(), messageId)));
    }

    /// The page just before or just after a message of the room (`before`/`after` are id
    /// parameters, 404 unless in the room), else the newest page.
    public List<Message> page(Room room, @Nullable String before, @Nullable String after) {
        return db.read(conn -> {
            if (before != null) return Message.pageBefore(conn, room.id(), pivot(conn, room, before));
            if (after != null) return Message.pageAfter(conn, room.id(), pivot(conn, room, after));
            return Message.lastPage(conn, room.id());
        });
    }

    public List<Safe> render(View v, List<Message> messages) {
        return db.read(conn -> new MessagePresenter(conn, v).render(messages));
    }

    public Safe render(View v, Message message) {
        return render(v, List.of(message)).getFirst();
    }

    public Editing editing(View v, Message message) {
        return db.read(conn -> {
            var presenter = new MessagePresenter(conn, v);
            return new Editing(presenter.view(message), presenter.editableBody(message));
        });
    }

    /// Stores the message and broadcasts it, then hands it to bots and push in the background.
    /// The upload is copied into storage and the body canonicalized before the write transaction,
    /// which then only inserts rows; the attachment is analyzed after it. `attachment` is an
    /// upload, "" for none, or anything else (which Rails rejects with a 500).
    public Posted post(View detached, Room room, User creator, @Nullable String body, @Nullable Object attachment,
                       @Nullable String clientMessageId) {
        Storage.Staged staged = attachment instanceof Params.Upload upload ? storage.stage(upload.path(), upload.filename(), upload.contentType()) : null;
        if (attachment != null && staged == null && !(attachment instanceof String s && s.isEmpty())) {
            throw new IllegalStateException("Could not find or build blob: expected attachable");
        }
        Prepared prepared = prepare(detached, body, null);
        Message message;
        try (staged) {
            record Created(Message message, @Nullable Blob blob) {}
            Created created = db.write(tx -> {
                Blob blob = staged == null ? null : storage.insert(tx, staged);
                Message m = Message.create(tx, room.id(), creator.id(), clientMessageId, prepared.body(), blob == null ? null : blob.id(),
                    searchText(prepared.plainText(), blob));
                return new Created(m, blob);
            });
            message = created.message();
            if (created.blob() != null) {
                storage.processAttachment(created.blob());
                long id = message.id();
                message = db.read(conn -> Message.find(conn, id));
            }
        }
        Message posted = message;
        Safe html = db.read(conn -> {
            Safe rendered = new MessagePresenter(conn, detached).render(posted);
            broadcasts.messageCreate(conn, room, posted, rendered.toString());
            return rendered;
        });
        delivery.posted(room, posted);
        return new Posted(posted, html);
    }

    /// A bot's answer from its webhook: text, or a file whose blob is saved on its own first. It
    /// is broadcast and pushed like any message, but not handed on to other bots.
    public Message postReply(View detached, Room room, User bot, @Nullable String text, Storage.@Nullable Staged file) {
        Message message;
        if (file != null) {
            Blob blob;
            try (file) {
                blob = db.write(tx -> storage.insert(tx, file));
            }
            message = db.write(tx -> Message.create(tx, room.id(), bot.id(), null, null, blob.id(), searchText("", blob)));
            storage.processAttachment(blob);
            long id = message.id();
            message = db.read(conn -> Message.find(conn, id));
        } else {
            Prepared prepared = prepare(detached, text, null);
            message = db.write(tx -> Message.create(tx, room.id(), bot.id(), null, prepared.body(), null, prepared.plainText()));
        }
        Message posted = message;
        db.read(conn -> {
            broadcasts.messageCreate(conn, room, posted, new MessagePresenter(conn, detached).render(posted).toString());
            return null;
        });
        delivery.pushLater(posted);
        return posted;
    }

    /// Replaces the body (a missing one leaves it as it was and just touches the message), then
    /// the body alone on everyone's screen, keeping their scroll position.
    public Message edit(View detached, Room room, Message message, @Nullable String body) {
        Prepared prepared = prepare(detached, body, message);
        Message edited = db.write(tx -> prepared.body() == null ? Message.touch(tx, message, prepared.plainText())
            : Message.updateBody(tx, message, prepared.body(), prepared.plainText()));
        String html = db.read(conn -> MessageFragments.presentation(detached, new MessagePresenter(conn, detached).view(edited)).toString());
        broadcasts.messageReplace(room, edited, html);
        return edited;
    }

    /// Swaps the message's attachment for an upload, or removes it for "" (what the bot API's edit
    /// does when given one). The old blob is purged and the new one analyzed in the background, but
    /// not processed as a newly posted one is; each change touches the message.
    public Message replaceAttachment(View detached, Room room, Message message, Object attachment) {
        Storage.Staged staged = attachment instanceof Params.Upload upload ? storage.stage(upload.path(), upload.filename(), upload.contentType()) : null;
        if (staged == null && !(attachment instanceof String s && s.isEmpty())) {
            throw new IllegalStateException("Could not find or build blob: expected attachable");
        }
        Message edited;
        try (staged) {
            edited = db.write(tx -> {
                Message current = message;
                var existing = Attachment.find(tx, Message.RECORD_TYPE, message.id(), "attachment");
                if (existing != null) {
                    Attachment.delete(tx, existing.id());
                    long purged = existing.blobId();
                    tx.afterCommit(() -> storage.purgeLater(purged));
                    current = Message.touch(tx, current, null);
                }
                if (staged != null) {
                    Blob blob = storage.insert(tx, staged);
                    Attachment.create(tx, Message.RECORD_TYPE, message.id(), "attachment", blob.id(), Times.now());
                    current = Message.touch(tx, current, null);
                    if (!Storage.analyzed(blob)) tx.afterCommit(() -> storage.analyzeLater(blob.id()));
                }
                return current;
            });
        }
        String html = db.read(conn -> MessageFragments.presentation(detached, new MessagePresenter(conn, detached).view(edited)).toString());
        broadcasts.messageReplace(room, edited, html);
        return edited;
    }

    /// Deletes the message; its attachment's files are purged in the background.
    public void delete(Room room, Message message) {
        Long blobId = db.write(tx -> Message.destroy(tx, message, true));
        if (blobId != null) storage.purgeLater(blobId);
        broadcasts.messageRemove(room, message);
    }

    /// A body ready to store: canonical Action Text HTML and the plain text the search index gets.
    /// `body` null keeps the existing message's.
    private record Prepared(@Nullable String body, String plainText) {}

    /// What the search index holds for a new message (`Message#plain_text_body`): its text, else
    /// its attachment's file name, so attachment-only messages are found by name.
    private static String searchText(String plainText, @Nullable Blob blob) {
        return !plainText.isBlank() || blob == null ? plainText : blob.filename();
    }

    private Prepared prepare(View v, @Nullable String body, @Nullable Message existing) {
        return db.read(conn -> {
            var presenter = new MessagePresenter(conn, v);
            if (body == null) return new Prepared(null, existing == null ? "" : presenter.plainTextBody(existing));
            String canonical = RichText.canonical(body, presenter.richText());
            return new Prepared(canonical, RichText.plainText(canonical, presenter.richText()));
        });
    }

    private static Message pivot(Conn conn, Room room, String id) {
        Long parsed = Params.parseId(id);
        return RecordNotFound.require(parsed == null ? null : Message.findInRoom(conn, room.id(), parsed));
    }
}
