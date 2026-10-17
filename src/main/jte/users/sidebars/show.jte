@import campfire.view.*
@import campfire.web.Paths
@param View v
@param campfire.sidebar.SidebarPage sidebar
@template.layouts.application(v = v, content = @`${v.sidebarTurboFrameTag(null, @`
  ${View.turboStreamFrom(sidebar.roomsStream())}
  ${View.turboStreamFrom(sidebar.userRoomsStream())}

  <div class="sidebar__container overflow-y overflow-hide-scrollbar"
      data-controller="badge-dot"
      data-badge-dot-unread-class="unread"
      data-action="rooms-list:unread@window->badge-dot#update rooms-list:read@window->badge-dot#update turbo:submit-start->turbo-frame#unpermanize">
    <turbo-frame id="direct_rooms_control" target="_top">
      <div class="directs gap overflow-x overflow-hide-scrollbar">
        ${View.linkTo(Paths.newRoomsDirect(), Attrs.of().set("class", "direct direct__new").data("turbo_frame", "_self"), @`
          <span class="avatar avatar--icon">
            ${v.imageTag("messages-add.svg", Attrs.of().set("size", 20).aria("hidden", "true").set("class", "colorize--black"))}
          </span>

          <span class="direct__author flex max-width min-width border-radius pad-inline-half">
            <span class="for-screen-reader">New</span>
            <span class="txt-small overflow-clip">Ping</span>
          </span>
`)}
        <div id="direct_rooms" contents data-controller="sorted-list" data-action="rooms-list:unread@window->sorted-list#updateItem">
          @for(Safe membership : sidebar.directRooms())${membership}@endfor        </div>

        <div contents>
          @for(var user : sidebar.directPlaceholderUsers())@template.users.sidebars.rooms._direct_placeholder(v = v, user = user)@endfor        </div>
      </div>
    </turbo-frame>

    <div class="rooms position-relative flex flex-column gap">
      <div id="shared_rooms" contents data-controller="sorted-list">
@for(var room : sidebar.sharedRooms())          @template.users.sidebars.rooms._shared(v = v, room = room)@endfor      </div>

@if(sidebar.canCreateRooms())        ${View.linkTo(Paths.newRoomsOpen(), Attrs.of().set("class", "rooms__new-btn btn room align-center gap txt-reversed").aria("label", "New Chat Room"), @`
          ${v.imageTag("add.svg", Attrs.of().set("size", 20).aria("hidden", "true").set("style", "view-transition-name: new-room"))}
`)}@endif    </div>

    <button class="btn sidebar__toggle" data-action="toggle-class#toggle">
      ${v.imageTag("menu.svg", Attrs.of().set("size", 20).aria("hidden", "true"))}
      <span class="for-screen-reader">Open menu</span>
    </button>
  </div>

  <div class="flex align-end sidebar__tools gap justify-end">
    ${View.linkTo(Paths.userProfile(), Attrs.of().set("class", "btn avatar flex-item-no-shrink sidebar__tool"), @`
      ${v.imageTag(v.avatarPath(sidebar.currentUser()), Attrs.of().set("size", 48).aria("hidden", "true").set("style", "view-transition-name: avatar-" + sidebar.currentUser().id()))}
      <span class="for-screen-reader">My Settings</span>
`)}
    ${View.linkTo(Paths.editAccount(), Attrs.of().set("class", "btn align-center gap txt-reversed sidebar__tool"), @`
      ${v.imageTag("settings.svg", Attrs.of().set("size", 20).aria("hidden", "true").set("style", "view-transition-name: account-settings"))}
      <span class="for-screen-reader">Account Settings</span>
`)}  </div>
`)}`)