@import campfire.rooms.RoomForms.FormRoom
@import campfire.view.*
@import gg.jte.Content
@param View v
@param FormRoom room
@param boolean canAdminister
@param boolean open
@param Content content
<form action="${room.action(open)}" accept-charset="UTF-8" method="post">@if(room.persisted())<input type="hidden" name="_method" value="patch" />@endif
  <div class="flex align-center gap">@if(canAdminister)
      ${v.translationButton("room_name")}

      <label class="flex-item-grow txt-large">
        <input name="room[name]" id="room_name" class="input full-width" required="required" autofocus="autofocus" placeholder="Name the room" data-turbo-permanent="true" data-action="keydown.enter-&gt;form#submit:prevent" type="text"@if(room.name() != null) value="${room.name()}"@endif />
        <span class="for-screen-reader">Name this room</span>
      </label>@else
      <h1 class="flex-item-grow txt-x-large">
        ${room.displayName()}
      </h1>@endif
  </div>

  <hr class="margin-block borderless">

  <section class="room-access margin-block pad-inline fill-shade border-radius">
    ${content}
  </section>@if(canAdminister)
  <button name="button" type="submit" class="btn btn--reversed txt-large center"><img aria-hidden="true" src="${v.asset("check.svg")}" width="20" height="20" /><span class="for-screen-reader">Save</span></button>@endif
</form>