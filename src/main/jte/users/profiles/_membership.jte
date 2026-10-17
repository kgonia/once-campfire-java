@import campfire.view.View
@import campfire.web.Paths
@param View v
@param campfire.users.UserPages.ProfileMembership membership
<li class="flex align-center gap margin-none min-width membership-item">
  <a class="overflow-ellipsis fill-shade txt-primary txt-undecorated" href="${Paths.room(membership.roomId())}">
    <strong>${membership.roomDisplayName()}</strong>
  </a>

  <hr class="separator" aria-hidden="true">

  <span class="txt-small">
    <turbo-frame id="${View.domId(membership.roomParamKey(), membership.roomId(), "involvement")}">
      @template.rooms.involvements._button(v = v, roomId = membership.roomId(), paramKey = membership.roomParamKey(), direct = membership.direct(), involvement = membership.involvement())
    </turbo-frame>
  </span>
</li>
