package campfire.bots;

import campfire.storage.AttachmentChange;
import campfire.users.User;
import campfire.view.Pages;
import campfire.web.CurrentRequest;
import campfire.web.Formats;
import campfire.web.Halt;
import campfire.web.Paths;
import campfire.web.Responses;
import gg.jte.generated.precompiled.accounts.bots.JteeditGenerated;
import gg.jte.generated.precompiled.accounts.bots.JteindexGenerated;
import gg.jte.generated.precompiled.accounts.bots.JtenewGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The account's bots, for administrators: listing them with the commands that post as them,
/// adding, editing and removing them, and replacing a bot's key.
@Path("/account/bots")
@RunOnVirtualThread
@NullMarked
public class BotAdminResource {
    @Inject
    CurrentRequest request;
    @Inject
    Pages pages;
    @Inject
    BotAdminService bots;

    @GET
    public Response index() {
        requireAdministrator();
        Formats.respondTo(request, "html");
        var listed = bots.bots();
        return pages.page((out, v) -> JteindexGenerated.render(out, null, v, listed));
    }

    @GET
    @Path("new")
    public Response newBot() {
        requireAdministrator();
        Formats.respondTo(request, "html");
        return pages.page((out, v) -> JtenewGenerated.render(out, null, v));
    }

    @POST
    public Response create() {
        requireAdministrator();
        Map<String, Object> params = botParams();
        bots.create(text(params, "name"), text(params, "webhook_url"), AttachmentChange.of(params, "avatar"));
        return Responses.redirect(request.url(Paths.accountBots()));
    }

    @GET
    @Path("{id}/edit")
    public Response edit(@PathParam("id") String id) {
        requireAdministrator();
        User bot = bots.bot(id);
        Formats.respondTo(request, "html");
        var form = bots.form(bot, request.baseUrl());
        return pages.page((out, v) -> JteeditGenerated.render(out, null, v, bot.id(), form));
    }

    @PUT
    @Path("{id}")
    public Response replace(@PathParam("id") String id) {
        return update(id);
    }

    @PATCH
    @Path("{id}")
    public Response update(@PathParam("id") String id) {
        requireAdministrator();
        User bot = bots.bot(id);
        Map<String, Object> params = botParams();
        bots.update(bot, text(params, "name"), text(params, "webhook_url"), AttachmentChange.of(params, "avatar"));
        return Responses.redirect(request.url(Paths.accountBots()));
    }

    @DELETE
    @Path("{id}")
    public Response destroy(@PathParam("id") String id) {
        requireAdministrator();
        bots.deactivate(bots.bot(id));
        return Responses.redirect(request.url(Paths.accountBots()));
    }

    @PUT
    @Path("{bot_id}/key")
    public Response replaceKey(@PathParam("bot_id") String botId) {
        return updateKey(botId);
    }

    /// The bot's old key stops working.
    @PATCH
    @Path("{bot_id}/key")
    public Response updateKey(@PathParam("bot_id") String botId) {
        requireAdministrator();
        bots.resetKey(bots.bot(botId));
        return Responses.redirect(request.url(Paths.accountBots()));
    }

    /// The `user` attributes; a `user` that isn't a hash fails as Rails does.
    @SuppressWarnings("unchecked")
    private Map<String, Object> botParams() {
        request.require("user");
        if (!(request.params().get("user") instanceof Map<?, ?> attributes)) throw new IllegalStateException("undefined method 'permit' for String");
        return (Map<String, Object>) attributes;
    }

    private static @Nullable String text(Map<String, Object> params, String key) {
        return params.get(key) instanceof String s ? s : null;
    }

    private void requireAdministrator() {
        if (!request.user().canAdminister()) throw new Halt(Responses.head(403));
    }
}
