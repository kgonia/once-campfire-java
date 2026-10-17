@import campfire.bots.BotAdminService.Listed
@import campfire.web.Paths
@param campfire.view.View v
@param java.util.List<Listed> bots
@template.layouts.application(v = v, title = "Chat bots", nav = @`
  <div class="flex-item-justify-start">
    @template.helpers._back_link(v = v, destination = Paths.editAccount())
  </div>
`, content = @`
  <section class="panel panel--wide txt-align-center flex flex-column position-relative" style="view-transition-name: chat-bots">
    <div class="flex align-center gap">
      <div class="panel__button">
        @template.helpers._translation_button(v = v, key = "chat_bots")
      </div>
      <div class="pad-inline-double center">
        <h1 class="margin-none">Chat bots</h1>
        <p class="margin-none-block-start">With Chat bots, other sites and services can post updates directly to Campfire.</p>

        <a class="btn btn--reversed txt-large" aria-label="Add a chat bot" href="${Paths.newAccountBot()}">
          <img aria-hidden="true" src="${v.asset("bot.svg")}" width="20" height="20">
          <img aria-hidden="true" src="${v.asset("add.svg")}" width="20" height="20">
        </a>
      </div>
    </div>

    <div class="pad-inline pad-block-start ">
      <menu class="flex flex-column gap margin-none pad">
        @for(Listed bot : bots)
          @template.accounts.bots._bot(v = v, bot = bot)
        @endfor
      </menu>
    </div>
  </section>
`)
