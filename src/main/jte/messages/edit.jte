@import campfire.messages.MessageView
@import campfire.view.View
@param View v
@param MessageView message
@param String editableBodyHtml
@template.layouts.application(v = v, content = @`@template.messages._edit(v = v, message = message, editableBodyHtml = editableBodyHtml)`)