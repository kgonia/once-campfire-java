@import campfire.bots.BotAdminService
@import campfire.web.Paths
@param campfire.view.View v
@param long botId
@param BotAdminService.Form bot
@template.layouts.application(v = v, title = "Edit bot", nav = @`
  <div class="flex-item-justify-start">
    @template.helpers._back_link(v = v, destination = Paths.accountBots())
  </div>
`, content = @`
  <section class="panel" style="view-transition-name: chat-bot-${botId}">
    <form class="flex flex-column gap" enctype="multipart/form-data" action="${Paths.accountBot(botId)}" accept-charset="UTF-8" method="post">
      <input type="hidden" name="_method" value="patch">
      @template.accounts.bots._form(v = v, bot = bot)
    </form>

    <hr class="separator full-width margin-block-double">

    <div class="flex align-center gap justify-space-between">
      <form class="button_to" method="post" action="${Paths.accountBot(botId)}">
        <input type="hidden" name="_method" value="delete">
        <button class="btn txt--small btn--negative" aria-label="Delete this chat bot" data-turbo-confirm="Are you sure you want to permanently remove this bot from the account? This can’t be undone." type="submit">
          <img aria-hidden="true" src="${v.asset("trash.svg")}" width="20" height="20">
          <img aria-hidden="true" src="${v.asset("bot.svg")}" width="20" height="20">
        </button>
      </form>

      <form class="button_to" method="post" action="${Paths.accountBotKey(botId)}">
        <input type="hidden" name="_method" value="put">
        <button class="btn full-width txt--small btn--negative" aria-label="Generate a new key" data-turbo-confirm="Are you sure you want to change the bot key? All usage of this bot must be updated." type="submit">
          <img aria-hidden="true" src="${v.asset("refresh.svg")}" width="20" height="20">
          <img aria-hidden="true" src="${v.asset("key.svg")}" width="20" height="20">
        </button>
      </form>
    </div>
  </section>
`)
