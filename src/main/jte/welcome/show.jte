@import campfire.web.Paths
@param campfire.view.View v
@param String currentUserName
@template.layouts.application(v = v, sidebar = @`
  @template.users.sidebars._frame(src = Paths.userSidebar())
`, content = @`
  <div id="message-area" class="message-area">
    <div class="message-area--empty min-width center">
      <figure class="center pad">
        <img aria-hidden="true" class="colorize--black translucent" src="${v.asset("messages-empty.svg")}">
        <span class="for-screen-reader">${currentUserName}</span>
      </figure>
    </div>
  </div>
`)
