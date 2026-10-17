@import campfire.messages.*
@param campfire.view.View v
@param MessageView message
<div id="${message.domId("presentation")}" dir="auto" data-reply-target="body" data-messages-target="body">
  ${MessagePresentation.of(v, message)}
</div>