package campfire.messages;

import campfire.App;
import campfire.db.Conn;
import campfire.db.Times;
import campfire.rails.GlobalId;
import campfire.rails.Secrets;
import campfire.richtext.RichText;
import campfire.richtext.RichTextRecord;
import campfire.rooms.RoomNames;
import campfire.storage.Attachment;
import campfire.storage.Blob;
import campfire.storage.Storage;
import campfire.users.User;
import campfire.users.UserCache;
import campfire.users.UserView;
import campfire.view.Safe;
import campfire.view.View;
import campfire.web.Params;
import campfire.web.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Builds message views from rows: bodies, boosts, creators and attachments of a page of messages
/// are each loaded in one query. Bound to one connection and one view context.
@NullMarked
public final class MessagePresenter {
    private final Conn conn;
    private final App app;
    private final View v;
    private final UserCache users;
    private final RoomNames rooms;
    private final RichText.Context richText;

    public MessagePresenter(Conn conn, View v) {
        this.conn = conn;
        this.app = v.app;
        this.v = v;
        this.users = new UserCache(conn);
        this.rooms = new RoomNames(conn);
        // Link previews are checked against the host without its port.
        this.richText = new RichText.Context(new MentionResolver(), MessageFragments.host(v).replaceFirst(":\\d+$", ""));
    }

    public RichText.Context richText() {
        return richText;
    }

    /// The rendered messages, in order. Fragments already cached are reused without loading
    /// anything more for them.
    public List<Safe> render(List<Message> messages) {
        String host = MessageFragments.host(v);
        var out = new ArrayList<@Nullable Safe>(messages.size());
        var missing = new ArrayList<Message>();
        for (Message m : messages) {
            Safe cached = MessageFragments.cachedMessage(m, host);
            out.add(cached);
            if (cached == null) missing.add(m);
        }
        var rendered = new ArrayList<Safe>(out.size());
        var views = views(missing).iterator();
        for (Safe html : out) rendered.add(html != null ? html : MessageFragments.message(v, views.next()));
        return rendered;
    }

    public Safe render(Message message) {
        return render(List.of(message)).getFirst();
    }

    public List<MessageView> views(List<Message> messages) {
        if (messages.isEmpty()) return List.of();
        List<Long> ids = messages.stream().map(Message::id).toList();
        Map<Long, String> bodies = RichTextRecord.messageBodies(conn, ids);
        Map<Long, List<Boost.WithBooster>> boosts = Boost.forMessages(conn, ids);
        Map<Long, Blob> blobs = Attachment.blobsFor(conn, Message.RECORD_TYPE, "attachment", ids);
        users.preload(messages.stream().map(Message::creatorId).toList());
        var views = new ArrayList<MessageView>(messages.size());
        for (Message m : messages) views.add(view(m, bodies.get(m.id()), boosts.getOrDefault(m.id(), List.of()), blobs.get(m.id())));
        return views;
    }

    public MessageView view(Message message) {
        return views(List.of(message)).getFirst();
    }

    public BoostView boostView(Boost boost, User booster) {
        return new BoostView(boost.id(), boost.updatedAt(), boost.messageId(), boost.content(), MessagePresentation.allEmoji(boost.content()),
            UserView.of(booster, v));
    }

    /// The body as plain text: mentions as names, attachments as their captions.
    public String plainTextBody(Message message) {
        var body = RichTextRecord.find(conn, Message.RECORD_TYPE, message.id(), "body");
        return RichText.plainText(body == null ? null : body.body(), richText);
    }

    /// The body as stored, rendered in its layout; "" for none.
    public String bodyHtml(Message message) {
        var body = RichTextRecord.find(conn, Message.RECORD_TYPE, message.id(), "body");
        return RichText.html(body == null ? null : body.body(), richText);
    }

    /// The room's members the body mentions (by verified signed id).
    public List<User> mentionees(Message message) {
        var body = RichTextRecord.find(conn, Message.RECORD_TYPE, message.id(), "body");
        return User.inRoomAmong(conn, message.roomId(), RichText.mentionedUserIds(body == null ? null : body.body(), richText));
    }

    /// The body as the editor loads it, "" for none.
    public String editableBody(Message message) {
        var body = RichTextRecord.find(conn, Message.RECORD_TYPE, message.id(), "body");
        String editable = RichText.editableValue(body == null ? null : body.body(), richText);
        return editable == null ? "" : editable;
    }

    private MessageView view(Message m, @Nullable String body, List<Boost.WithBooster> boosts, @Nullable Blob blob) {
        String roomName = rooms.displayName(m.roomId());
        User creator = users.find(m.creatorId());
        if (creator == null) {
            return new MessageView(m.id(), m.clientMessageId(), m.roomId(), roomName, new UserView(m.creatorId(), "", "", ""), m.createdAt(),
                m.updatedAt(), false, new MessageContent.Unrenderable(), List.of());
        }
        String plainText = RichText.plainText(body, richText);
        var boostViews = new ArrayList<BoostView>(boosts.size());
        for (var b : boosts) boostViews.add(boostView(b.boost(), b.booster()));
        return new MessageView(m.id(), m.clientMessageId(), m.roomId(), roomName, UserView.of(creator, v), m.createdAt(), m.updatedAt(),
            MessagePresentation.allEmoji(plainText), content(body, plainText, blob), boostViews);
    }

    private MessageContent content(@Nullable String body, String plainText, @Nullable Blob blob) {
        if (blob != null) return new MessageContent.Attachment(attachment(blob));
        Sound sound = Sound.in(plainText);
        if (sound != null) {
            var image = sound.image();
            return new MessageContent.Sound(v.asset(sound.assetPath()), image == null ? null : v.asset(image.assetPath()),
                image == null ? 0 : image.width(), image == null ? 0 : image.height(), sound.text());
        }
        return new MessageContent.Text(RichText.presentation(body, richText));
    }

    private AttachmentView attachment(Blob blob) {
        Storage storage = app.storage();
        boolean visual = storage.previewable(blob) || storage.variable(blob);
        AttachmentView.Preview preview = !visual ? AttachmentView.Preview.FILE : blob.isVideo() ? AttachmentView.Preview.VIDEO : AttachmentView.Preview.IMAGE;
        return new AttachmentView(blob.filename(), storage.blobPath(blob), storage.blobPath(blob, "attachment"), preview,
            visual ? storage.thumbPath(blob) : null, blob.metadataNumber("width"), blob.metadataNumber("height"));
    }

    /// Mentions in rich text name users by signed or plain global id; a mention of a deleted user
    /// renders as nothing.
    private final class MentionResolver implements RichText.Resolver {
        @Override
        public RichText.@Nullable MentionUser locateSigned(String sgid) {
            GlobalId gid = app.secrets().locateSigned(sgid, Secrets.ATTACHABLE_PURPOSE, Times.now());
            return gid == null || !gid.modelName().equals("User") ? null : mention(gid.id());
        }

        @Override
        public RichText.@Nullable MentionUser findGid(String gid) {
            GlobalId parsed = GlobalId.parse(gid);
            return parsed == null || !parsed.modelName().equals("User") ? null : mention(parsed.id());
        }

        private RichText.@Nullable MentionUser mention(String id) {
            Long userId = Params.parseId(id);
            User user = userId == null ? null : users.find(userId);
            if (user == null) return null;
            return new RichText.MentionUser(user.id(), user.name(), user.title(), app.secrets().attachableSgid(GlobalId.of("User", user.id())),
                Paths.user(user.id()), v.avatarPath(user));
        }
    }
}
