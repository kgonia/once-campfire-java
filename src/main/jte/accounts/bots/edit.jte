@import campfire.bots.BotAdminService
@import campfire.view.*
@import campfire.web.Paths
@param View v
@param long botId
@param BotAdminService.Form bot
!{var form = View.formWith(Paths.accountBot(botId)).model("user").method("patch").cssClass("flex flex-column gap");}@template.layouts.application(v = v, title = "Edit bot",
  nav = @`  <div class="flex-item-justify-start">
    ${v.linkBackTo(Paths.accountBots())}
  </div>
`,
  content = @`<section class="panel" style="view-transition-name: chat-bot-${botId}">
  ${form.wrap(@`
    @template.accounts.bots._form(v = v, form = form, bot = bot)
`)}
  <hr class="separator full-width margin-block-double">

  <div class="flex align-center gap justify-space-between">
    ${View.buttonTo(Paths.accountBot(botId), "delete", Attrs.of().set("class", "btn txt--small btn--negative").aria("label", "Delete this chat bot").data("turbo_confirm", "Are you sure you want to permanently remove this bot from the account? This can’t be undone."), @`
      ${v.imageTag("trash.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
      ${v.imageTag("bot.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
`)}
    ${View.buttonTo(Paths.accountBotKey(botId), "put", Attrs.of().set("class", "btn full-width txt--small btn--negative").aria("label", "Generate a new key").data("turbo_confirm", "Are you sure you want to change the bot key? All usage of this bot must be updated."), @`
      ${v.imageTag("refresh.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
      ${v.imageTag("key.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
`)}  </div>
</section>
`)