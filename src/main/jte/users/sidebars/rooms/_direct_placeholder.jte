@import campfire.view.*
@param View v
@param campfire.users.User user
${View.buttonTo(View.roomsDirectsWithUser(user.id()), Attrs.of().set("class", "direct borderless fill-transparent unpad"), @`
  <span class="avatar">
    ${v.imageTag(v.avatarPath(user), Attrs.of().aria("hidden", "true"))}
  </span>

  <span class="direct__author flex align-center gap max-width min-width border-radius txt-small">
    <span class="txt-nowrap overflow-ellipsis">
      <span class="for-screen-reader">Start a ping with</span>
      ${user.firstName()}
    </span>
  </span>
`)}