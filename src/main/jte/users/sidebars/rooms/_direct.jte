@import campfire.sidebar.DirectRoom
@import campfire.view.*
@param View v
@param DirectRoom membership

  ${v.linkToRoom(membership.roomId(), Attrs.of().set("class", membership.classNames()).set("id", View.domId("rooms_direct", membership.roomId(), "list")).data("sorted_list_number", String.valueOf(membership.updatedAtEpoch())), @`@if(membership.members().size() > 1)
      <div class="avatar__group">@for(var member : membership.members().stream().limit(4).toList())
          <span class="avatar">
            ${v.imageTag(v.avatarPath(member), Attrs.of().set("size", 20).aria("hidden", "true"))}
          </span>@endfor
      </div>@else
      <span class="avatar">
        ${v.imageTag(v.avatarPath(membership.members().getFirst()), Attrs.of().set("size", 48).aria("hidden", "true"))}
      </span>@endif

    <span class="direct__author flex align-center gap max-width min-width border-radius txt-small">
      <span class="txt-nowrap overflow-ellipsis">
        <span class="for-screen-reader">Ping with</span>@if(membership.members().size() > 1)
          ${membership.memberInitials()}@else
          ${membership.members().getFirst().firstName()}@endif
      </span>
    </span>
`)}