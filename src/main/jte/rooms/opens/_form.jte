@import campfire.rooms.RoomForms.OpenForm
@import campfire.users.UserView
@import campfire.view.*
@param View v
@param OpenForm form
@param String typeChangePath
@template.rooms.layouts._form(v = v, room = form.room(), canAdminister = form.canAdminister(), open = true, content = @`${v.userFilterMenuTag(@`
    <li class="flex align-center gap margin-none">
      <figure class="avatar flex-item-no-shrink" style="--avatar-border-radius: 0; --avatar-size: 4ch;">
        <img aria-hidden="true" class="colorize--black" style="background-color: transparent" src="${v.asset("everyone.svg")}" />
        <span class="for-screen-reader">Everyone</span>
      </figure>

      <div class="min-width">
        <div class="overflow-ellipsis fill-shade"><strong>Everyone</strong></div>
      </div>

      <hr class="separator" aria-hidden="true">@if(form.canAdminister())
        <a class="btn--faux flex-inline" tabindex="-1" data-turbo-action="replace" href="${typeChangePath}">
          <label for="room_type" class="switch">
            <input type="checkbox" id="room_type" class="switch__input" checked="checked">
            <span class="switch__btn round"></span>
            <span class="for-screen-reader">Give only some access to this room</span>
          </label>
</a>@endif
    </li>

    <hr class="separator full-width" style="--border-style: solid">@if(form.users().size() > 20)
    ${v.userFilterSearchTag()}@endif

    <div data-filter-target="list" contents>@for(UserView user : form.users())
      @template.rooms.opens._user(v = v, user = user, canAdminister = form.canAdminister())@endfor
    </div>
`)}`)