@import campfire.view.Safe
@import campfire.rooms.RefreshedMessage
@param String target
@param java.util.List<Safe> newMessages
@param java.util.List<RefreshedMessage> updatedMessages
@if(!newMessages.isEmpty())<turbo-stream action="append" target="${target}"><template>
  @for(Safe message : newMessages)${message}@endfor
</template></turbo-stream>@endif
@for(RefreshedMessage message : updatedMessages)  <turbo-stream action="replace" target="${message.target()}"><template>${message.html()}</template></turbo-stream>
@endfor