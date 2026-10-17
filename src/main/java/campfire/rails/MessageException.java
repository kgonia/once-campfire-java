package campfire.rails;

/// Why a signed or encrypted message couldn't be read. Thrown without a stack trace: verifying
/// untrusted input fails routinely.
public final class MessageException extends RuntimeException {
    public enum Kind {
        /// Malformed message or bad signature (Ruby's `:invalid_message_format`).
        INVALID_SIGNATURE,
        /// Authentic, but the payload doesn't deserialize.
        INVALID_MESSAGE,
        EXPIRED,
        /// Wrong purpose, or a purpose was expected and the message carries no metadata.
        PURPOSE_MISMATCH;

        /// `ActiveSupport::Messages::Rotator` falls back to the next rotation only on format and
        /// serialization errors.
        boolean rotates() {
            return this == INVALID_SIGNATURE || this == INVALID_MESSAGE;
        }
    }

    private final Kind kind;

    public MessageException(Kind kind) {
        super(kind.name(), null, false, false);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
