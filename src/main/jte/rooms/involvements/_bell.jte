@import campfire.rooms.*
@import campfire.web.Paths
@param campfire.view.View v
@param RoomView room
<span>
  <span class="button_to_change_notifying"
      data-controller="notifications" data-notifications-subscriptions-url-value="${Paths.userPushSubscriptions()}" data-notifications-attention-class="btn--pulsing">
    <turbo-frame data-controller="turbo-frame" data-action="notifications:ready@window->turbo-frame#load" data-turbo-frame-url-param="${Paths.roomInvolvement(room.id())}" id="${room.domId("involvement")}">
      <button class="btn" data-action="click->notifications#attemptToSubscribe" data-notifications-target="bell">
        <img aria-hidden="true" src="${v.asset("notification-bell-loading.svg")}" width="20" height="20">
        <img aria-hidden="true" hidden src="${v.asset("notification-bell-alert.svg")}" width="20" height="20">
        <span class="for-screen-reader">Notification settings for this ${room.noun()}</span>
      </button>
    </turbo-frame>

    <dialog data-notifications-target="notAllowedNotice" class="dialog pad center center-block border-radius border shadow" style="--inline-space: var(--block-space)">
      <div class="flex flex-column txt-align-center">
        <span class="btn btn--faux center txt-x-large">
          <img aria-hidden="true" src="${v.asset("notification-bell-alert.svg")}" width="48" height="48">
          <span class="for-screen-reader">Notifications alert</span>
        </span>

        <section>
          <h1 class="txt-large margin-none">Notifications aren’t allowed</h1>
          <div class="txt-align-start margin-block-start">
            @template.pwa._browser_settings(v = v)
            @template.pwa._system_settings(v = v)
            @template.pwa._install_instructions(v = v)
          </div>
        </section>

        <form method="dialog" class="flex align-center gap center">
          <button class="btn dialog__close" autofocus>
            <span class="for-screen-reader">Close</span>
            <img aria-hidden="true" src="${v.asset("remove.svg")}" width="20" height="20">
          </button>
        </form>
      </div>
    </dialog>
  </span>
</span>
