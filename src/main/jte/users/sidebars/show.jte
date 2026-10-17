@import campfire.view.Safe
@import campfire.web.Paths
@param campfire.view.View v
@param campfire.sidebar.SidebarPage sidebar
@template.layouts.application(v = v, content = @`
  @template.users.sidebars._frame(content = @`
    <turbo-cable-stream-source channel="Turbo::StreamsChannel" signed-stream-name="${sidebar.roomsStream()}"></turbo-cable-stream-source>
    <turbo-cable-stream-source channel="Turbo::StreamsChannel" signed-stream-name="${sidebar.userRoomsStream()}"></turbo-cable-stream-source>

    <div class="sidebar__container overflow-y overflow-hide-scrollbar"
        data-controller="badge-dot"
        data-badge-dot-unread-class="unread"
        data-action="rooms-list:unread@window->badge-dot#update rooms-list:read@window->badge-dot#update turbo:submit-start->turbo-frame#unpermanize">
      <turbo-frame id="direct_rooms_control" target="_top">
        <div class="directs gap overflow-x overflow-hide-scrollbar">
          <a class="direct direct__new" data-turbo-frame="_self" href="${Paths.newRoomsDirect()}">
            <span class="avatar avatar--icon">
              <img aria-hidden="true" class="colorize--black" src="${v.asset("messages-add.svg")}" width="20" height="20">
            </span>

            <span class="direct__author flex max-width min-width border-radius pad-inline-half">
              <span class="for-screen-reader">New</span>
              <span class="txt-small overflow-clip">Ping</span>
            </span>
          </a>

          <div id="direct_rooms" contents data-controller="sorted-list" data-action="rooms-list:unread@window->sorted-list#updateItem">
            @for(Safe membership : sidebar.directRooms())
              ${membership}
            @endfor
          </div>

          <div contents>
            @for(var user : sidebar.directPlaceholderUsers())
              @template.users.sidebars.rooms._direct_placeholder(v = v, user = user)
            @endfor
          </div>
        </div>
      </turbo-frame>

      <div class="rooms position-relative flex flex-column gap">
        <div id="shared_rooms" contents data-controller="sorted-list">
          @for(var room : sidebar.sharedRooms())
            @template.users.sidebars.rooms._shared(room = room)
          @endfor
        </div>

        @if(sidebar.canCreateRooms())
          <a class="rooms__new-btn btn room align-center gap txt-reversed" aria-label="New Chat Room" href="${Paths.newRoomsOpen()}">
            <img aria-hidden="true" style="view-transition-name: new-room" src="${v.asset("add.svg")}" width="20" height="20">
          </a>
        @endif
      </div>

      <button class="btn sidebar__toggle" data-action="toggle-class#toggle">
        <img aria-hidden="true" src="${v.asset("menu.svg")}" width="20" height="20">
        <span class="for-screen-reader">Open menu</span>
      </button>
    </div>

    <div class="flex align-end sidebar__tools gap justify-end">
      <a class="btn avatar flex-item-no-shrink sidebar__tool" href="${Paths.userProfile()}">
        <img aria-hidden="true" style="view-transition-name: avatar-${sidebar.currentUser().id()}" src="${v.avatarPath(sidebar.currentUser())}" width="48" height="48">
        <span class="for-screen-reader">My Settings</span>
      </a>

      <a class="btn align-center gap txt-reversed sidebar__tool" href="${Paths.editAccount()}">
        <img aria-hidden="true" style="view-transition-name: account-settings" src="${v.asset("settings.svg")}" width="20" height="20">
        <span class="for-screen-reader">Account Settings</span>
      </a>
    </div>
  `)
`)
