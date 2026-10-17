@import campfire.rooms.RoomForms.FormRoom
@import gg.jte.Content
@param campfire.view.View v
@param FormRoom room
@param boolean canAdminister
@param boolean open
@param Content content
<form action="${room.action(open)}" accept-charset="UTF-8" method="post">
  @if(room.persisted())
    <input type="hidden" name="_method" value="patch">
  @endif
  <div class="flex align-center gap">
    @if(canAdminister)
      @template.helpers._translation_button(v = v, key = "room_name")

      <label class="flex-item-grow txt-large">
        <input name="room[name]" id="room_name" class="input full-width" required autofocus placeholder="Name the room" data-turbo-permanent="true" data-action="keydown.enter->form#submit:prevent" type="text" value="${room.name()}">
        <span class="for-screen-reader">Name this room</span>
      </label>
    @else
      <h1 class="flex-item-grow txt-x-large">
        ${room.displayName()}
      </h1>
    @endif
  </div>

  <hr class="margin-block borderless">

  <section class="room-access margin-block pad-inline fill-shade border-radius">
    ${content}
  </section>
  @if(canAdminister)
    <button name="button" type="submit" class="btn btn--reversed txt-large center"><img aria-hidden="true" src="${v.asset("check.svg")}" width="20" height="20"><span class="for-screen-reader">Save</span></button>
  @endif
</form>
