@import campfire.view.*
@import campfire.web.Paths
@param View v
@param campfire.users.UserPages.ProfileMembership membership
<li class="flex align-center gap margin-none min-width membership-item">
  ${View.linkTo(Paths.room(membership.roomId()), Attrs.of("class", "overflow-ellipsis fill-shade txt-primary txt-undecorated"), @`
    <strong>${membership.roomDisplayName()}</strong>
`)}
  <hr class="separator" aria-hidden="true">

  <span class="txt-small">
    ${View.turboFrameTag(View.domId(membership.roomParamKey(), membership.roomId(), "involvement"), null, null, Attrs.of(), @`
      ${v.buttonToChangeInvolvement(membership.roomId(), membership.roomParamKey(), membership.direct(), membership.involvement())}
`)}  </span>
</li>
