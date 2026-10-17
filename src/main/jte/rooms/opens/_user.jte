@import campfire.users.UserView
@import campfire.view.*
@param View v
@param UserView user
@param boolean canAdminister
<li class="flex align-center gap margin-none" data-value="${user.name().toLowerCase(java.util.Locale.ROOT)}">
  <figure class="avatar flex-item-no-shrink" style="--avatar-size: 4ch;">
    <a title="${user.title()}" class="btn avatar" data-turbo-frame="_top" href="${user.path()}"><img aria-hidden="true" loading="lazy" src="${user.avatarUrl()}" width="48" height="48" /></a>
  </figure>

  <div class="min-width">
    <div class="overflow-ellipsis fill-shade"><strong>${user.name()}</strong></div>
  </div>

  <hr class="separator" aria-hidden="true">@if(canAdminister)
    <img class="colorize--black flex-item-no-shrink" aria-hidden="true" src="${v.asset("check.svg")}" width="20" height="20" />@endif
</li>