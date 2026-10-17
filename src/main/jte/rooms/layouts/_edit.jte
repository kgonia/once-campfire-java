@import campfire.web.Paths
@import gg.jte.Content
@param campfire.view.View v
@param long roomId
@param String name
@param boolean canAdminister
@param Content roomForm
@template.layouts.application(v = v, title = "Edit settings for " + name, nav = @`
  <div class="flex-item-justify-start">
    @template.helpers._back_link(v = v, destination = v.lastRoomVisitedPath())
  </div>
`, content = @`
  <section class="panel txt-align-center" style="view-transition-name: edit-room-${roomId}">
    ${roomForm}
  </section>
  @if(canAdminister)
    <section class="panel txt-align-center">
      <form class="button_to" method="post" action="${v.url(Paths.room(roomId))}">
        <input type="hidden" name="_method" value="delete">
        <button class="btn btn--negative max-width" aria-label="Delete ${name}" data-turbo-confirm="Are you sure you want to delete this room and all messages in it? This can’t be undone." type="submit">
          <img aria-hidden="true" src="${v.asset("trash.svg")}" width="20" height="20">
          <span class="overflow-ellipsis">${name}</span>
        </button>
      </form>
    </section>
  @endif
`)
