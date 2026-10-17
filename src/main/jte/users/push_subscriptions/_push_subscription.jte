@import campfire.view.*
@import campfire.web.Paths
@param View v
@param campfire.push.PushSubscriptionView pushSubscription
<li class="flex flex-column margin-none membership-item">
  <span class="overflow-ellipsis txt-primary txt-undecorated">
    <strong>${pushSubscription.browser()} ${pushSubscription.version()} on ${pushSubscription.platform()}</strong><br>
  </span>

  <span class="flex align-start gap txt-small">
    <span>${pushSubscription.endpoint()}</span>

    <span class="flex align-center gap">
      ${View.buttonTo(Paths.userPushSubscriptionTestNotifications(pushSubscription.id()), Attrs.of("class", "btn btn--reversed"), @`
        ${v.imageTag("notification-bell-everything.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
        <span class="for-screen-reader">Send test notification</span>
`)}
      ${View.buttonTo(Paths.userPushSubscription(pushSubscription.id()), Attrs.of("method", "delete", "class", "btn btn--negative"), @`
        ${v.imageTag("minus.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
        <span class="for-screen-reader">Delete subscription</span>
`)}    </span>
  </span>
</li>
