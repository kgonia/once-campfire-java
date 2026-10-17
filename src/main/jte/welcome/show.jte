@import campfire.view.*
@import campfire.web.Paths
@param View v
@param String currentUserName
@template.layouts.application(v = v,
  sidebar = @`${v.sidebarTurboFrameTag(Paths.userSidebar(), null)}`,
  content = @`<div id="message-area" class="message-area">
  <div class="message-area--empty min-width center">
    <figure class="center pad">
      ${v.imageTag("messages-empty.svg", Attrs.of().aria("hidden", "true").set("class", "colorize--black translucent"))}
      <span class="for-screen-reader">${currentUserName}</span>
    </figure>
  </div>
</div>
`)