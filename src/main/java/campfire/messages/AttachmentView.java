package campfire.messages;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// A message's attached file. `previewUrl` is the thumbnail of an image or video, null for other
/// files. `width`/`height` come from the blob's analyzed metadata: an Integer for images, a Double
/// for videos, null before analysis or for other files.
@NullMarked
public record AttachmentView(String filename, String blobPath, String downloadPath, Preview preview, @Nullable String previewUrl,
                             @Nullable Number width, @Nullable Number height) {
    public enum Preview { VIDEO, IMAGE, FILE }
}
