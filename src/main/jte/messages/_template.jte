@import campfire.users.UserView
@param campfire.view.View v
@param UserView user
<%-- What the composer clones for a message being sent; the $…$ placeholders are its own. --%>
<script type="text/template" data-messages-target="template">
  <div class="message message--me $messageClasses$"
      id="message_$clientMessageId$"
      data-format-message-target="message"
      data-user-id="${user.id()}"
      data-message-timestamp="$messageTimestamp$"
      data-messages-target="message">
    <div class="message__day-separator"><time class="message__timestamp" datetime="$messageDatetime$" data-local-time-target="date"></time></div>

    <figure class="avatar message__avatar">
      <a title="${user.title()}" class="btn avatar" data-turbo-frame="_top" href="${user.path()}"><img aria-hidden="true" src="${user.avatarUrl()}" width="48" height="48"></a>
    </figure>

    <div class="message__body">
      <div class="message__body-content">
        <div class="message__meta">
          <h3 class="message__heading">
            <span class="message__author"><strong>${user.name()}</strong></span>
            <span class="message__permalink"><time class="message__timestamp" datetime="$messageDatetime$" data-local-time-target="time"></time></span>
          </h3>
          <div class="message__actions">
            <div class="position-relative">
              <span class="btn message__action-btn message__options-btn">
                <img class="colorize--black" aria-hidden="true" src="${v.asset("menu-dots-horizontal.svg")}">
                <span class="for-screen-reader">Message options</span>
              </span>
            </div>
          </div>
        </div>
        $body$
      </div>
    </div>
  </div>
</script>
