@import gg.jte.Content
@param campfire.view.View v
@param Content roomForm
@template.layouts.application(v = v, title = "New chat room", nav = @`
  <div class="flex-item-justify-start">
    @template.helpers._back_link(v = v, destination = v.lastRoomVisitedPath())
  </div>
`, content = @`
  <section class="panel txt-align-center" style="view-transition-name: new-room">
    ${roomForm}
  </section>
`)
