@import campfire.bots.BotAdminService
@import campfire.view.*
@import campfire.web.Paths
@param View v
!{var form = View.formWith(Paths.accountBots()).model("user").cssClass("flex flex-column gap");}@template.layouts.application(v = v, title = "New chat bot",
  nav = @`  <div class="flex-item-justify-start">
    ${v.linkBackTo(Paths.accountBots())}
  </div>
`,
  content = @`<section class="panel">
  ${form.wrap(@`
    @template.accounts.bots._form(v = v, form = form, bot = BotAdminService.Form.NEW)
`)}</section>
`)