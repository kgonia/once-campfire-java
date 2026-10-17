@import campfire.sidebar.DirectRoom
@import campfire.view.View
@import campfire.web.Paths
@param View v
@param DirectRoom membership
<a class="${membership.classNames()}" id="${View.domId("rooms_direct", membership.roomId(), "list")}" data-rooms-list-target="room" data-room-id="${membership.roomId()}" data-badge-dot-target="unread" data-sorted-list-target="item" data-sorted-list-number="${membership.updatedAtEpoch()}" href="${Paths.room(membership.roomId())}">
  @if(membership.members().size() > 1)
    <div class="avatar__group">
      @for(var member : membership.members().stream().limit(4).toList())
        <span class="avatar">
          <img aria-hidden="true" src="${v.avatarPath(member)}" width="20" height="20">
        </span>
      @endfor
    </div>
  @else
    <span class="avatar">
      <img aria-hidden="true" src="${v.avatarPath(membership.members().getFirst())}" width="48" height="48">
    </span>
  @endif

  <span class="direct__author flex align-center gap max-width min-width border-radius txt-small">
    <span class="txt-nowrap overflow-ellipsis">
      <span class="for-screen-reader">Ping with</span>
      @if(membership.members().size() > 1)
        ${membership.memberInitials()}
      @else
        ${membership.members().getFirst().firstName()}
      @endif
    </span>
  </span>
</a>
