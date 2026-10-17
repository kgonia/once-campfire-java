@import campfire.rooms.RoomForms.OpenForm
@import campfire.web.Paths
@param campfire.view.View v
@param OpenForm form
@template.rooms.layouts._new(v = v, roomForm = @`
  @template.rooms.opens._form(v = v, form = form, typeChangePath = Paths.newRoomsClosed())
`)
