@import campfire.messages.*
@param campfire.view.View v
@param MessageView message
@if(message.unrenderable())
  @template.messages._unrenderable()@else
  <div id="${message.domId("")}" class="message ${message.allEmoji() ? "message--emoji" : ""}" data-controller="reply" data-user-id="${message.creator().id()}" data-message-id="${message.id()}" data-message-timestamp="${message.createdAtEpoch()}" data-message-updated-at="${message.updatedAtEpoch()}" data-sort-value="${message.createdAtEpoch()}" data-messages-target="message" data-search-results-target="message" data-refresh-room-target="message" data-reply-composer-outlet="#composer">
    <h2 class="message__day-separator"><time datetime="${message.createdAtIso()}" data-local-time-target="date"></time></h2>

    <figure class="avatar message__avatar">
      <a title="${message.creator().title()}" class="btn avatar" data-turbo-frame="_top" href="${message.creator().path()}"><img aria-hidden="true" src="${message.creator().avatarUrl()}" width="48" height="48" /></a>
    </figure>

    <turbo-frame id="${message.domId("edit")}">
      <div class="message__body">
        <div class="message__body-content">
          <div class="message__meta">
            <h3 class="message__heading">
              <span class="message__author" title="${message.creator().title()}">
                <strong data-reply-target="author">${message.creator().name()}</strong>
              </span>
              <a target="_top" class="message__permalink" href="${message.atPath()}"><time class="message__timestamp" datetime="${message.createdAtIso()}" data-local-time-target="time"></time></a>
              <span class="message__room">
                <a target="_top" data-reply-target="link" href="${message.atPath()}">${message.roomName()}</a>
              </span>
            </h3>
            @template.messages._actions(v = v, message = message)
          </div>
          @template.messages._presentation(v = v, message = message)
          @template.messages.boosts._boosts(v = v, message = message)
        </div>
      </div>
    </turbo-frame>
</div>@endif