@import campfire.bots.BotAdminService
@param campfire.view.View v
@param BotAdminService.Form bot
<h1 class="for-screen-reader">Chat Bot Setup</h1>
<label class="align-center center avatar__form gap" data-controller="upload-preview">
  <div class="btn input--file">
    <img aria-hidden="true" src="${v.asset("camera.svg")}" width="20" height="20">
    <input class="input" accept="image/*" data-upload-preview-target="input" data-action="upload-preview#previewImage" type="file" name="user[avatar]" id="user_avatar">
    <span class="for-screen-reader">Upload bot avatar</span>
  </div>

  <div class="avatar input--file txt-xx-large" style="--avatar-size: var(--btn-size);">
    <img alt="Bot avatar" data-upload-preview-target="image" src="${bot.avatarUrl() != null ? bot.avatarUrl() : v.asset("default-bot-avatar.svg")}" width="48" height="48">
  </div>
</label>

<div class="flex align-center gap">
  @template.helpers._translation_button(v = v, key = "bot_name")
  <label class="flex align-center gap flex-item-grow txt-large input input--actor">
    <input class="input" autocomplete="name" placeholder="Name the bot" autofocus required data-1p-ignore="true" type="text" value="${bot.name()}" name="user[name]" id="user_name">
    <img aria-hidden="true" class="colorize--black" src="${v.asset("bot.svg")}" width="24" height="24">
  </label>
</div>

<div class="flex align-center gap">
  @template.helpers._translation_button(v = v, key = "webhook_url")
  <label class="flex align-center gap flex-item-grow txt-large input input--actor">
    <input class="input" placeholder="Webhook URL" type="url" value="${bot.webhookUrl()}" name="user[webhook_url]" id="user_webhook_url">
    <img aria-hidden="true" class="colorize--black" src="${v.asset("web.svg")}" width="24" height="24">
  </label>
</div>

<button class="btn btn--reversed center txt-large" type="submit">
  <img aria-hidden="true" src="${v.asset("check.svg")}" width="20" height="20">
  <span class="for-screen-reader">Save changes</span>
</button>
