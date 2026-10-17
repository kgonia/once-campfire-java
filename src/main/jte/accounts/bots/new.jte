@import campfire.bots.BotAdminService
@import campfire.web.Paths
@param campfire.view.View v
@template.layouts.application(v = v, title = "New chat bot", nav = @`
  <div class="flex-item-justify-start">
    @template.helpers._back_link(v = v, destination = Paths.accountBots())
  </div>
`, content = @`
  <section class="panel">
    <form class="flex flex-column gap" enctype="multipart/form-data" action="${Paths.accountBots()}" accept-charset="UTF-8" method="post">
      @template.accounts.bots._form(v = v, bot = BotAdminService.Form.NEW)
    </form>
  </section>
`)
