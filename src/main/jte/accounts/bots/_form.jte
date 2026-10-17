@import campfire.bots.BotAdminService
@import campfire.view.*
@param View v
@param Form form
@param BotAdminService.Form bot
<h1 class="for-screen-reader">Chat Bot Setup</h1>
<label class="align-center center avatar__form gap" data-controller="upload-preview">
  <div class="btn input--file">
    ${v.imageTag("camera.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
    ${form.fileField("avatar", Attrs.of().set("class", "input").set("accept", "image/*").data("upload_preview_target", "input").data("action", "upload-preview#previewImage"))}
    <span class="for-screen-reader">Upload bot avatar</span>
  </div>

  <div class="avatar input--file txt-xx-large" style="--avatar-size: var(--btn-size);">
    ${v.imageTag(bot.avatarUrl() != null ? bot.avatarUrl() : "default-bot-avatar.svg", Attrs.of().set("alt", "Bot avatar").set("size", 48).data("upload_preview_target", "image"))}
  </div>
</label>

<div class="flex align-center gap">
  ${v.translationButton("bot_name")}
  <label class="flex align-center gap flex-item-grow txt-large input input--actor">
    ${form.textField("name", bot.name(), Attrs.of().set("class", "input").set("autocomplete", "name").set("placeholder", "Name the bot").set("autofocus", true).set("required", true).data("1p-ignore", true))}
    ${v.imageTag("bot.svg", Attrs.of().aria("hidden", "true").set("size", 24).set("class", "colorize--black"))}
  </label>
</div>

<div class="flex align-center gap">
  ${v.translationButton("webhook_url")}
  <label class="flex align-center gap flex-item-grow txt-large input input--actor">
    ${form.urlField("webhook_url", bot.webhookUrl(), Attrs.of().set("class", "input").set("placeholder", "Webhook URL"))}
    ${v.imageTag("web.svg", Attrs.of().aria("hidden", "true").set("size", 24).set("class", "colorize--black"))}
  </label>
</div>

${v.profileFormSubmitButton()}
