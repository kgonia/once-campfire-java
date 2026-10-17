@import campfire.bots.BotAdminService.Listed
@import campfire.view.*
@import campfire.web.Paths
@param View v
@param java.util.List<Listed> bots
@template.layouts.application(v = v, title = "Chat bots",
  nav = @`  <div class="flex-item-justify-start">
    ${v.linkBackTo(Paths.editAccount())}
  </div>
`,
  content = @`<section class="panel panel--wide txt-align-center flex flex-column position-relative" style="view-transition-name: chat-bots">
  <div class="flex align-center gap">
    <div class="panel__button">
      ${v.translationButton("chat_bots")}
    </div>
    <div class="pad-inline-double center">
      <h1 class="margin-none">Chat bots</h1>
      <p class="margin-none-block-start">With Chat bots, other sites and services can post updates directly to Campfire.</p>

      ${View.linkTo(Paths.newAccountBot(), Attrs.of().set("class", "btn btn--reversed txt-large").aria("label", "Add a chat bot"), @`
        ${v.imageTag("bot.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
        ${v.imageTag("add.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
`)}    </div>
  </div>

  <div class="pad-inline pad-block-start ">
    <menu class="flex flex-column gap margin-none pad">
      @for(Listed bot : bots)@template.accounts.bots._bot(v = v, bot = bot)@endfor    </menu>
  </div>
</section>
`)