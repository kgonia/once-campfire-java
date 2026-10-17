package campfire.web;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/// Ends a request early with a finished response (a redirect, a bare status), from anywhere below
/// a resource method. `ErrorPages` lets it through untouched.
public final class Halt extends WebApplicationException {
    public Halt(Response response) {
        super(response);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
