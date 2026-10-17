@import campfire.rooms.*
@import campfire.view.*
@import campfire.web.Paths
@param View v
@param RoomPage show
@template.layouts.application(v = v, title = show.room().displayName(), bodyClass = "sidebar",
  head = @`
  <meta name="turbo-cache-control" content="no-preview">
  <meta name="current-room-id" content="${show.room().id()}">`,
  nav = @`@template.rooms.show._nav(v = v, room = show.room())`,
  sidebar = @`${v.sidebarTurboFrameTag(Paths.userSidebar(), null)}`,
  content = @`
<div id="message-area" class="message-area" contents="true" data-controller="messages presence drop-target" data-action="turbo:before-stream-render@document-&gt;messages#beforeStreamRender keydown.up@document-&gt;messages#editMyLastMessage dragenter-&gt;drop-target#dragenter dragover-&gt;drop-target#dragover drop-&gt;drop-target#drop visibilitychange@document-&gt;presence#visibilityChanged" data-messages-first-of-day-class="message--first-of-day" data-messages-formatted-class="message--formatted" data-messages-me-class="message--me" data-messages-mentioned-class="message--mentioned" data-messages-threaded-class="message--threaded" data-messages-page-url-value="${v.url(Paths.roomMessages(show.room().id()))}">
  @template.messages._template(v = v, user = show.user())

  <div id="${show.room().domId("messages")}" class="messages" data-controller="maintain-scroll refresh-room" data-action="turbo:before-stream-render@document-&gt;maintain-scroll#beforeStreamRender visibilitychange@document-&gt;refresh-room#visibilityChanged online@window-&gt;refresh-room#online" data-messages-target="messages" data-refresh-room-loaded-at-value="${show.loadedAt()}" data-refresh-room-url-value="${v.url(Paths.roomRefresh(show.room().id()))}">@if(show.invitation())
    @template.rooms.show._invitation(v = v, joinCode = show.joinCode())@endif@for(Safe message : show.messages())
    ${message.handed()}@endfor
  </div>

  <turbo-cable-stream-source channel="RoomMessagesChannel" signed-stream-name="${show.messagesStreamName()}"></turbo-cable-stream-source>
  <button class="message-area__return-to-latest btn" data-action="messages#returnToLatest" data-messages-target="latest" hidden="hidden"><img aria-hidden="true" src="${v.asset("arrow-down.svg")}" width="20" height="20" /><span class="for-screen-reader">Jump to newest message</span></button>
</div>`,
  footer = @`@template.rooms.show._composer(v = v, room = show.room())`)