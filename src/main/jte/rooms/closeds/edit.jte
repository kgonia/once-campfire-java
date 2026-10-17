@import campfire.rooms.RoomForms.ClosedForm
@import campfire.web.Paths
@param campfire.view.View v
@param ClosedForm form
@template.rooms.layouts._edit(v = v, roomId = form.room().idOrZero(), name = form.room().displayName(), canAdminister = form.canAdminister(), roomForm = @`
  @template.rooms.closeds._form(v = v, form = form, typeChangePath = Paths.editRoomsOpen(form.room().idOrZero()))
`)
