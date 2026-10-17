@import campfire.web.Paths
@param campfire.view.View v
@param campfire.users.User user
@if(user.isActive())
  <form class="button_to" method="post" action="${Paths.userBan(user.id())}">
    <button class="btn full-width" data-turbo-confirm="Are you sure you want to ban this user? This will log them out, delete their messages, and block their IP addresses." type="submit">
      <img aria-hidden="true" aria-label="Ban ${user.name()}" src="${v.asset("cancel.svg")}">
      <span>Ban ${user.name()}</span>
    </button>
  </form>
@else
  <form class="button_to" method="post" action="${Paths.userBan(user.id())}">
    <input type="hidden" name="_method" value="delete">
    <button class="btn btn--negative full-width" data-turbo-confirm="Are you sure you want to remove the ban on this user?" type="submit">
      <img aria-hidden="true" aria-label="Remove Ban ${user.name()}" src="${v.asset("cancel.svg")}">
      <span>Remove ban</span>
    </button>
  </form>
@endif
