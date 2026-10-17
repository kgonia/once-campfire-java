@import campfire.view.*
@import gg.jte.Content
@param View v
@param Content roomForm
@template.layouts.application(v = v, title = "New chat room",
  nav = @`
  <div class="flex-item-justify-start">
    ${v.linkBackToLastRoomVisited()}
  </div>`,
  content = @`
<section class="panel txt-align-center" style="view-transition-name: new-room">
  ${roomForm}
</section>`)