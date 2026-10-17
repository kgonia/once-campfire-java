package campfire.messages;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// What a message shows in its body.
@NullMarked
public sealed interface MessageContent {
    /// The rich text body as sanitized, auto-linked HTML.
    record Text(String html) implements MessageContent {}

    /// A `/play <name>` message: the sound's mp3, and the image (or else the text) shown beside
    /// the play button.
    record Sound(String url, @Nullable String imageSrc, int imageWidth, int imageHeight, @Nullable String text) implements MessageContent {}

    record Attachment(AttachmentView attachment) implements MessageContent {}

    /// The message couldn't be rendered (its creator is gone); a placeholder shows instead.
    record Unrenderable() implements MessageContent {}
}
