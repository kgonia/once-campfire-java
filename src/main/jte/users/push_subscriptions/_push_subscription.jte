@import campfire.web.Paths
@param campfire.view.View v
@param campfire.push.PushSubscriptionView pushSubscription
<li class="flex flex-column margin-none membership-item">
  <span class="overflow-ellipsis txt-primary txt-undecorated">
    <strong>${pushSubscription.browser()} ${pushSubscription.version()} on ${pushSubscription.platform()}</strong><br>
  </span>

  <span class="flex align-start gap txt-small">
    <span>${pushSubscription.endpoint()}</span>

    <span class="flex align-center gap">
      <form class="button_to" method="post" action="${Paths.userPushSubscriptionTestNotifications(pushSubscription.id())}">
        <button class="btn btn--reversed" type="submit">
          <img aria-hidden="true" src="${v.asset("notification-bell-everything.svg")}" width="20" height="20">
          <span class="for-screen-reader">Send test notification</span>
        </button>
      </form>

      <form class="button_to" method="post" action="${Paths.userPushSubscription(pushSubscription.id())}">
        <input type="hidden" name="_method" value="delete">
        <button class="btn btn--negative" type="submit">
          <img aria-hidden="true" src="${v.asset("minus.svg")}" width="20" height="20">
          <span class="for-screen-reader">Delete subscription</span>
        </button>
      </form>
    </span>
  </span>
</li>
