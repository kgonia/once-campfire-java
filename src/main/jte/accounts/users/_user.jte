@import campfire.users.User
@import campfire.view.View
@import campfire.web.Paths
@param View v
@param User user
!{String roleId = View.domId("user", user.id(), "role");}
!{boolean current = v.isCurrentUser(user.id());}
<li class="flex align-center gap margin-none ${user.isBanned() ? "banned" : ""}">
  <figure class="avatar flex-item-no-shrink" style="--avatar-size: 3.75ch;">
    @template.users._avatar(v = v, user = user, lazy = true)
  </figure>

  <div class="min-width">
    <div class="overflow-ellipsis fill-shade"><strong>${user.name()}</strong></div>
  </div>

  <hr class="separator" aria-hidden="true">

  @if(v.canAdminister() && user.isActive())
    @if(!user.isBot())
      <form data-controller="form" action="${Paths.accountUser(user.id())}" accept-charset="UTF-8" method="post">
        <input type="hidden" name="_method" value="patch">
        <label class="btn txt-small flex-item-no-shrink" for="${roleId}">
          <span class="for-screen-reader">Role: ${user.isAdministrator() ? "Administrator" : "Member"}</span>
          <img aria-hidden="true" src="${v.asset("crown.svg")}" width="20" height="20">
          <input name="user[role]" disabled="${current}" type="hidden" value="member">
          <input data-action="form#submit" hidden id="${roleId}" disabled="${current}" type="checkbox" value="administrator" checked="${"administrator".equals(user.roleName())}" name="user[role]">
        </label>
      </form>
    @endif

    @if(!current)
      <form class="button_to" method="post" action="${Paths.accountUser(user.id())}">
        <input type="hidden" name="_method" value="delete">
        <button class="btn txt-small flex-item-no-shrink btn--negative" data-turbo-confirm="Are you sure you want to permanently remove this person from the account? This can’t be undone." type="submit">
          <img aria-hidden="true" src="${v.asset("minus.svg")}" width="20" height="20">
          <span class="for-screen-reader">Delete ${user.name()}</span>
        </button>
      </form>
    @endif
  @endif
  @if(current)
    <a class="btn txt-small flex-item-no-shrink" target="_top" href="${Paths.userProfile()}">
      <img aria-hidden="true" src="${v.asset("pencil.svg")}" width="20" height="20">
      <span class="for-screen-reader">My settings</span>
    </a>
  @endif
</li>
