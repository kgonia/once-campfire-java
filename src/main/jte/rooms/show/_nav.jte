@import campfire.rooms.*
@param campfire.view.View v
@param RoomView room
@if(v.accountHasLogo)
  @template.helpers._account_logo(v = v)
@endif

<span class="btn btn--reversed btn--faux room--current">
  <h1 class="room__contents txt-medium overflow-ellipsis">
    @if(room.direct())
      <span class="for-screen-reader">Ping with</span>
    @endif

    ${room.displayName()}
  </h1>
</span>

<a class="btn" style="view-transition-name: edit-room-${room.id()}" data-room-id="${room.id()}" href="${room.editPath()}">
  <img aria-hidden="true" src="${v.asset("menu-dots-horizontal.svg")}" width="20" height="20">
  <span class="for-screen-reader">Settings for this ${room.noun()}</span>
</a>

@template.rooms.involvements._bell(v = v, room = room)
