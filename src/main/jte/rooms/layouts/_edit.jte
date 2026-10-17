@import campfire.view.*
@import campfire.web.Paths
@import gg.jte.Content
@param View v
@param long roomId
@param String name
@param boolean canAdminister
@param Content roomForm
@template.layouts.application(v = v, title = "Edit settings for " + name,
  nav = @`
  <div class="flex-item-justify-start">
    ${v.linkBackToLastRoomVisited()}
  </div>`,
  content = @`
<section class="panel txt-align-center" style="view-transition-name: edit-room-${roomId}">
  ${roomForm}
</section>@if(canAdminister)
  <section class="panel txt-align-center">
    ${View.buttonTo(v.url(Paths.room(roomId)), "delete", Attrs.of("class", "btn btn--negative max-width").aria("label", "Delete " + name).data("turbo_confirm", "Are you sure you want to delete this room and all messages in it? This can’t be undone."), Safe.of("<img aria-hidden=\"true\" src=\"" + campfire.richtext.Html.escape(v.asset("trash.svg")) + "\" width=\"20\" height=\"20\" /><span class=\"overflow-ellipsis\">" + campfire.richtext.Html.escape(name) + "</span>"))}
  </section>@endif`)