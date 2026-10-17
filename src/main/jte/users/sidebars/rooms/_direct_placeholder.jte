@import campfire.view.View
@param View v
@param campfire.users.User user
<form class="button_to" method="post" action="${View.roomsDirectsWithUser(user.id())}">
  <button class="direct borderless fill-transparent unpad" type="submit">
    <span class="avatar">
      <img aria-hidden="true" src="${v.avatarPath(user)}">
    </span>

    <span class="direct__author flex align-center gap max-width min-width border-radius txt-small">
      <span class="txt-nowrap overflow-ellipsis">
        <span class="for-screen-reader">Start a ping with</span>
        ${user.firstName()}
      </span>
    </span>
  </button>
</form>
