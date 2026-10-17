@import campfire.rooms.RoomForms.ClosedForm
@import campfire.users.UserView
@import campfire.view.*
@param View v
@param ClosedForm form
@param UserView user
@param boolean selected
<li class="flex align-center gap margin-none" data-value="${user.name().toLowerCase(java.util.Locale.ROOT)}">
  <figure class="avatar flex-item-no-shrink" style="--avatar-size: 4ch;">
    <a title="${user.title()}" class="btn avatar" data-turbo-frame="_top" href="${user.path()}"><img aria-hidden="true" loading="lazy" src="${user.avatarUrl()}" width="48" height="48" /></a>
  </figure>

  <div class="min-width">
    <div class="overflow-ellipsis fill-shade"><strong>${user.name()}</strong></div>
  </div>

  <hr class="separator" aria-hidden="true">@if(form.canAdminister())@if(user.id() == form.currentUserId() && !form.room().persisted())
      <input type="hidden" name="user_ids[]" value="${user.id()}" />
      <img class="colorize--black flex-item-no-shrink" aria-hidden="true" src="${v.asset("check.svg")}" width="20" height="20" />@else
      <label class="switch flex-item-no-shrink">
        <input type="checkbox" name="user_ids[]" value="${user.id()}" class="switch__input"@if(selected) checked="checked"@endif />
        <span class="switch__btn round"></span>
        <span class="for-screen-reader">Give ${user.name()} access to this room</span>
      </label>@endif@endif
</li>