@import campfire.view.*
@import campfire.web.Paths
@param View v
<details class="notifications-help hide-in-browser" data-notifications-target="details">
  <summary class="btn">
    ${v.imageTag("external/gear.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
    <strong>Check your ${v.platform.operatingSystem()} settings</strong>
    ${v.imageTag("disclosure.svg", Attrs.of().aria("hidden", "true").set("size", 10).set("class", "disclosure"))}
  </summary>

@if(v.platform.firefox() && v.platform.android())      <ol>
        <li>Tap the <em>${v.imageTag("menu-dots-vertical.svg", Attrs.of().set("alt", "More options").set("size", 16))}</em> menu button.</li>
        <li>Tap <em>Settings</em>.</li>
        <li>Tap <em>Notifications</em>.</li>
        <li>Tap <em>${v.imageTag("external/switch.svg", Attrs.of().set("alt", "the toggle button").set("size", 22))}</em> to <em>Allow ${View.capitalize(v.platform.browser())} notifications</em>.</li>
      </ol>
@elseif(v.platform.edge() && v.platform.desktop())      <ol>
        <li>Click <em>Start</em>, then <em>Settings</em>.</li>
        <li>Go to <em>System &gt; Notification</em>.</li>
        <li>Click <em>${v.imageTag("external/switch.svg", Attrs.of().set("alt", "the toggle button").set("size", 22))}</em> <em>ON</em> for Campfire.</li>
      </ol>
@elseif((v.platform.firefox() || v.platform.chrome()) && v.platform.desktop())      <ol>
@if(v.platform.windows())          <li>Click <em>Start</em>, then <em>Settings</em>.</li>
          <li>Go to <em>System &gt; Notification</em>.</li>
          <li>Click <em>${v.imageTag("external/switch.svg", Attrs.of().set("alt", "the toggle button").set("size", 22))}</em> <em>ON</em> for Campfire.</li>
@else          <li>Click <em aria-label="the Apple menu"></em> in the top left.</li>
          <li>Click <em>System Settings…</em>.</li>
          <li>Click <em>Notifications</em>.</li>
          <li>Click <em>Campfire</em>.</li>
          <li>Click <em>${v.imageTag("external/switch.svg", Attrs.of().set("alt", "the allow notifications switch").set("size", 22))}</em> to <em>Allow notifications</em>.</li>
@endif      </ol>
@elseif(v.platform.safari() && v.platform.desktop())      <ol>
        <li>Click <em aria-label="the Apple menu"></em> in the top left.</li>
        <li>Click <em>System Settings…</em>.</li>
        <li>Click <em>Notifications</em>.</li>
        <li>Click <em>Campfire</em>.</li>
        <li>Click <em>${v.imageTag("external/switch.svg", Attrs.of().set("alt", "the allow notifications switch").set("size", 22))}</em> to <em>Allow notifications</em>.</li>
      </ol>
@elseif((v.platform.safari() || v.platform.chrome()) && v.platform.ios())      <ol>
        <li>Open the <em>${v.imageTag("external/gear.svg", Attrs.of().aria("hidden", "true").set("size", 20))}</em> Settings app.</li>
        <li>Scroll to and tap <em>Campfire</em>.</li>
        <li>Tap <em>Notifications</em>.</li>
        <li>Tap <em>${v.imageTag("external/switch.svg", Attrs.of().set("alt", "the allow notifications switch button").set("size", 22))}</em> to <em>Allow Notifications</em>.</li>
      </ol>
@elseif(v.platform.chrome() && v.platform.android())      <ol>
        <li>Open the <em>${v.imageTag("external/gear.svg", Attrs.of().aria("hidden", "true").set("size", 20))}</em> Settings app.</li>
        <li>Tap <em>Notifications</em>.</li>
        <li>Tap <em>App notifications</em>.</li>
        <li>Scroll to <em>Campfire</em>.</li>
        <li>Tap <em>${v.imageTag("external/switch.svg", Attrs.of().set("alt", "the switch").set("size", 22))}</em> to <em>Allow Notifications</em>.</li>
      </ol>
@else      <p>Ensure notifications are allowed for ${View.capitalize(v.platform.browser())} in your system settings.</p>
@endif</details>
