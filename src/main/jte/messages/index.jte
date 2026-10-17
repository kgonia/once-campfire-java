@import campfire.view.Safe
@import java.util.List
@param List<Safe> messages
@for(Safe message : messages)
  ${message.handed()}
@endfor
