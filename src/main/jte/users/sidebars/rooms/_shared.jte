@import campfire.view.View
@import campfire.web.Paths
@param campfire.sidebar.SidebarPage.SharedRoom room
<a id="${View.domId(room.paramKey(), room.id(), "list")}" data-rooms-list-target="room" data-room-id="${room.id()}" data-badge-dot-target="unread" data-sorted-list-target="item" data-sorted-list-name="${room.name()}" style="--column-gap: 0.5em" class="${room.classNames()}" href="${Paths.room(room.id())}">
  <span class="overflow-ellipsis">${room.name()}</span>
</a>
