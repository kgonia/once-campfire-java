@import campfire.rooms.RoomForms.OpenForm
@import campfire.web.Paths
@param campfire.view.View v
@param OpenForm form
@template.rooms.layouts._edit(v = v, roomId = form.room().idOrZero(), name = form.room().displayName(), canAdminister = form.canAdminister(), roomForm = @`
  @template.rooms.opens._form(v = v, form = form, typeChangePath = Paths.editRoomsClosed(form.room().idOrZero()))
`)
