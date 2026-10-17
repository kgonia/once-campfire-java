package campfire.web;

/// Answered with the static error page for `status` (Rails' exception pages), like a
/// {@link RecordNotFound} is with the 404.
public final class HttpError extends RuntimeException {
    private final int status;

    public HttpError(int status) {
        super("HTTP " + status, null, false, false);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
