package campfire.pwa;

import campfire.accounts.Account;
import campfire.view.Pages;
import campfire.view.View;
import campfire.web.Access;
import campfire.web.CurrentRequest;
import campfire.web.Formats;
import campfire.web.Paths;
import gg.jte.generated.precompiled.pwa.JtemanifestjsonGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.jspecify.annotations.NullMarked;

/// The web app manifest and the service worker, at stable URLs so installed apps keep finding
/// them. Fetched by the browser itself, hence no sign-in or cross-site check.
@Path("/{file: (?:service-worker|webmanifest)}")
@Access.Public
@Access.NoForgeryProtection
@RunOnVirtualThread
@NullMarked
public class PwaResource {
    private static final byte[] SERVICE_WORKER = load("/campfire/pwa/service_worker.js");

    @Inject
    CurrentRequest request;
    @Inject
    Pages pages;

    @GET
    public Response show(@PathParam("file") String file) {
        return file.equals("service-worker") ? serviceWorker() : manifest();
    }

    private Response serviceWorker() {
        Formats.respondTo(request, "js");
        return varied(Response.ok(SERVICE_WORKER).header("Content-Type", "text/javascript; charset=utf-8"));
    }

    /// The icons are the account's logo, versioned by the account so installed apps update.
    private Response manifest() {
        Formats.respondTo(request, "json");
        View v = pages.view();
        Account account = v.account;
        String name = account == null || account.name() == null ? "Campfire" : account.name();
        var updatedAt = account == null ? null : account.updatedAt();
        byte[] body = Pages.bytes(v, (out, view) -> JtemanifestjsonGenerated.render(out, null, view, name,
            Paths.freshAccountLogo(updatedAt, "small"), Paths.freshAccountLogo(updatedAt, null)));
        return varied(Response.ok(body).header("Content-Type", "application/json; charset=utf-8"));
    }

    private Response varied(Response.ResponseBuilder response) {
        if (Formats.variesOnAccept(request)) response.header("Vary", "Accept");
        return response.build();
    }

    private static byte[] load(String resource) {
        try (InputStream in = PwaResource.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing " + resource);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
