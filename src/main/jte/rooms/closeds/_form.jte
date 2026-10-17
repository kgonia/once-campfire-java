@import campfire.rooms.RoomForms.ClosedForm
@import campfire.users.UserView
@import campfire.view.*
@param View v
@param ClosedForm form
@param String typeChangePath
@template.rooms.layouts._form(v = v, room = form.room(), canAdminister = form.canAdminister(), open = false, content = @`${v.userFilterMenuTag(@`@if(form.canAdminister())
      <li class="flex align-center gap margin-none">
        <figure class="avatar flex-item-no-shrink" style="--avatar-border-radius: 0; --avatar-size: 4ch;">
          <img aria-hidden="true" class="colorize--black" style="background-color: transparent" src="${v.asset("everyone.svg")}" />
          <span class="for-screen-reader">Everyone</span>
        </figure>

        <div class="min-width">
          <div class="overflow-ellipsis fill-shade"><strong>Everyone</strong></div>
        </div>

        <hr class="separator" aria-hidden="true">

        <a class="btn--faux flex-inline" tabindex="-1" data-turbo-action="replace" href="${typeChangePath}">
          <label for="room_type" class="switch">
            <input type="checkbox" id="room_type" class="switch__input">
            <span class="switch__btn round"></span>
            <span class="for-screen-reader">Give everyone access to this room</span>
          </label>
</a>
      </li>

      <hr class="separator full-width" style="--border-style: solid">@endif@if(form.userCount() > 20)
    ${v.userFilterSearchTag()}@endif

    <div data-filter-target="list" contents>@for(UserView user : form.selectedUsers())
      @template.rooms.closeds._user(v = v, form = form, user = user, selected = true)@endfor@if(!form.selectedUsers().isEmpty() && !form.unselectedUsers().isEmpty())
        <hr class="separator full-width" style="--border-style: solid">@endif@for(UserView user : form.unselectedUsers())
      @template.rooms.closeds._user(v = v, form = form, user = user, selected = false)@endfor
    </div>
`)}`)