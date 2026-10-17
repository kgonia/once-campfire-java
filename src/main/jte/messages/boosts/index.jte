@import campfire.messages.*
@param campfire.view.View v
@param MessageView message
@template.layouts.application(v = v, content = @`
  @template.messages.boosts._boosts(v = v, message = message)
`)
