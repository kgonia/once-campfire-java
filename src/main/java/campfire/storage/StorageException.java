package campfire.storage;

/// An Active Storage operation that can't be done: an invalid variation, a blob that can't be
/// represented, a file that's missing or fails its checksum, a failed libvips/ffmpeg run.
public class StorageException extends RuntimeException {
    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    /// The tool a job needs (libvips, ffmpeg) isn't installed: callers fall back to the original.
    public static final class Unavailable extends StorageException {
        public Unavailable(String message) {
            super(message);
        }
    }
}
