@import campfire.rooms.RoomForms.ClosedForm
@import campfire.view.*
@import campfire.web.Paths
@param View v
@param ClosedForm form
@template.rooms.layouts._new(v = v, roomForm = @`@template.rooms.closeds._form(v = v, form = form, typeChangePath = Paths.newRoomsOpen())`)