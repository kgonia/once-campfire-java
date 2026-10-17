@param campfire.view.View v
@param java.util.List<campfire.push.PushSubscriptionView> pushSubscriptions
@template.layouts.application(v = v, title = "Push notification subscriptions", nav = @`
  <div class="flex-item-justify-start">
    @template.helpers._back_link(v = v, destination = v.lastRoomVisitedPath())
  </div>
`, content = @`
  <section class="panel panel--wide flex flex-column gap">
    <h1 class="txt-align-center txt-large margin-none">Push Notification Subscriptions</h1>
    <div class="pad-inline fill-shade border-radius" id="push_subscriptions">
      <menu class="pad flex flex-column gap">
        @for(var pushSubscription : pushSubscriptions)
          @template.users.push_subscriptions._push_subscription(v = v, pushSubscription = pushSubscription)
        @endfor
      </menu>
    </div>
  </section>
`)
