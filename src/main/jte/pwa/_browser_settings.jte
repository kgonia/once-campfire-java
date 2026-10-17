@import campfire.view.*
@import campfire.web.Paths
@param View v
@if(!((v.platform.safari() || v.platform.chrome()) && v.platform.ios()))  <details class="notifications-help" data-notifications-target="details">
    <summary class="btn">
      ${v.imageTag("external/web.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
      <strong>Check your ${View.capitalize(v.platform.browser())} settings</strong>
      ${v.imageTag("disclosure.svg", Attrs.of().aria("hidden", "true").set("size", 10).set("class", "disclosure"))}
    </summary>

@if(v.platform.firefox() && v.platform.android())        <ol>
          <li>Tap <em>${v.imageTag("lock.svg", Attrs.of().set("alt", "the View site information button").set("size", 20))}</em> in the address bar.</li>
          <li>Tap <em>Notification</em> to change to <em>Allowed</em>.</li>
        </ol>
@elseif(v.platform.edge() && v.platform.desktop())        <h2 class="txt-normal txt-medium margin-block-start">Turn on notifications for this website.</h2>
        <ol>
          <li>Click <em>${v.imageTag("lock.svg", Attrs.of().set("alt", "the View site information button").set("size", 20))}</em> left of the address bar.</li>
          <li>Under <em>Permissions for this site &gt; Notifications</em>, choose <em>Allow</em>.</li>
        </ol>
        <h2 class="txt-normal txt-medium margin-block-start">Turn on notifications for ${View.capitalize(v.platform.browser())}.</h2>
        <ol>
@if(v.platform.windows())            <li>Click <em>Start</em>, then <em>Settings</em>.</li>
            <li>Go to <em>System &gt; Notification</em>.</li>
            <li>Click <em>${v.imageTag("external/switch.svg", Attrs.of().set("alt", "the switch").set("size", 22))}</em> <em>ON</em> for ${View.capitalize(v.platform.browser())}.</li>
@else            <li>Click <em aria-label="the Apple menu"></em> in the top left.</li>
            <li>Click <em>System Settings…</em>.</li>
            <li>Click <em>Notifications</em>.</li>
            <li>Click <em>${View.capitalize(v.platform.browser())}</em>.</li>
            <li>Click <em>${v.imageTag("external/switch.svg", Attrs.of().set("alt", "the switch").set("size", 22))}</em> to <em>Allow notifications</em>.</li>
@endif        </ol>
@elseif(v.platform.firefox() && v.platform.desktop())        <h2 class="txt-normal txt-medium margin-block-start">Turn on notifications for this website.</h2>
        <ol>
          <li>Click <em>${View.capitalize(v.platform.browser())}</em> in the top left.</li>
          <li>Click <em>Settings…</em>.</li>
          <li>Click <em>Privacy & Security</em> in the sidebar.</li>
          <li>Scroll down to <em>Permissions</em>.</li>
          <li>Click <em>Settings</em> next to <em>Notifications</em>.</li>
          <li>Select <em>Allow</em> next to <em>${v.url("/")}</em>.</li>
        </ol>

        <h2 class="txt-normal txt-medium margin-block-start">Turn on notifications for ${View.capitalize(v.platform.browser())}.</h2>
        <ol>
@if(v.platform.windows())            <li>Click <em>Start</em>, then <em>Settings</em>.</li>
            <li>Go to <em>System &gt; Notification</em>.</li>
            <li>Click <em>${v.imageTag("external/switch.svg", Attrs.of().set("alt", "the toggle button").set("size", 22))}</em> <em>ON</em> for ${View.capitalize(v.platform.browser())}.</li>
@else            <li>Click <em aria-label="the Apple menu"></em> in the top left.</li>
            <li>Click <em>System Settings…</em>.</li>
            <li>Click <em>Notifications</em>.</li>
            <li>Click <em>${View.capitalize(v.platform.browser())}</em>.</li>
            <li>Click <em>${v.imageTag("external/switch.svg", Attrs.of().set("alt", "the switch").set("size", 22))}</em> to <em>Allow notifications</em>.</li>
@endif        </ol>
@elseif(v.platform.chrome() && v.platform.desktop())        <h2 class="txt-normal txt-medium margin-block-start">Turn on notifications for this website.</h2>
        <ol>
          <li>Click the <em>${v.imageTag("external/sliders.svg", Attrs.of().set("alt", "View site information").set("size", 20))}</em> icon in the address bar.</li>
          <li>Click <em>Site Settings</em>.</li>
          <li>Ensure notifications are <em>Allowed</em>.</li>
        </ol>

        <h2 class="txt-normal txt-medium margin-block-start">Turn on notifications for ${View.capitalize(v.platform.browser())}.</h2>
        <ol>
@if(v.platform.windows())            <li>Click <em>Start</em>, then <em>Settings</em>.</li>
            <li>Go to <em>System &gt; Notification</em>.</li>
            <li>Click <em>${v.imageTag("external/switch.svg", Attrs.of().set("alt", "the switch").set("size", 22))}</em> <em>ON</em> for ${View.capitalize(v.platform.browser())}.</li>
@else            <li>Click <em aria-label="the Apple menu"></em> in the top left.</li>
            <li>Click <em>System Settings…</em>.</li>
            <li>Click <em>Notifications</em>.</li>
            <li>Click <em>${View.capitalize(v.platform.browser())}</em>.</li>
            <li>Click <em>${v.imageTag("external/switch.svg", Attrs.of().set("alt", "the switch").set("size", 22))}</em> to <em>Allow notifications</em>.</li>
@endif        </ol>
@elseif(v.platform.chrome() && v.platform.android())        <ol>
          <li>Tap the <em>${v.imageTag("menu-dots-vertical.svg", Attrs.of().set("alt", "More options").set("size", 16))}</em> menu button.</li>
          <li>Tap <em>Settings</em>.</li>
          <li>Tap <em>Notifications</em>.</li>
          <li>Tap <em>${v.imageTag("external/switch.svg", Attrs.of().set("alt", "the switch").set("size", 22))}</em> to <em>Allow ${View.capitalize(v.platform.browser())} notifications</em>.</li>
          <li>Tap <em>${v.imageTag("external/switch.svg", Attrs.of().set("alt", "the switch").set("size", 22))}</em> next to <em>Web apps</em>.</li>
          <li>Tap <em>${v.imageTag("notification-bell-alert.svg", Attrs.of().set("alt", "the notification bell").set("size", 16))}</em> and select <em>Allow</em>.</li>
        </ol>
@elseif(v.platform.safari() && v.platform.desktop())        <ol>
          <li>Click <em>${View.capitalize(v.platform.browser())}</em> in the top left.</li>
          <li>Click <em>Settings…</em>.</li>
          <li>Click the <em>Websites</em> tab.</li>
          <li>Click <em>Notifications</em> in the sidebar.</li>
          <li>Click <em>${v.url("/")}</em> in the list.</li>
          <li>Select <em>Allow</em>.</li>
        </ol>
@else        <p>Ensure notifications are enabled for <em>${v.url("/")}</em> in your web browser settings.</p>
@endif  </details>
@endif