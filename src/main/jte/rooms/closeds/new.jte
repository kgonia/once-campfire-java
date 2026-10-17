@import campfire.rooms.RoomForms.ClosedForm
@import campfire.web.Paths
@param campfire.view.View v
@param ClosedForm form
@template.rooms.layouts._new(v = v, roomForm = @`
  @template.rooms.closeds._form(v = v, form = form, typeChangePath = Paths.newRoomsOpen())
`)
