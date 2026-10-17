@import campfire.view.*
@param View v
<details class="notifications-help hide-in-browser" data-notifications-target="details">
  <summary class="btn">
    <img aria-hidden="true" src="${v.asset("external/gear.svg")}" width="20" height="20">
    <strong>Check your ${v.platform.operatingSystem()} settings</strong>
    <img aria-hidden="true" class="disclosure" src="${v.asset("disclosure.svg")}" width="10" height="10">
  </summary>

    @if(v.platform.firefox() && v.platform.android())
      <ol>
        <li>Tap the <em><img alt="More options" src="${v.asset("menu-dots-vertical.svg")}" width="16" height="16"></em> menu button.</li>
        <li>Tap <em>Settings</em>.</li>
        <li>Tap <em>Notifications</em>.</li>
        <li>Tap <em><img alt="the toggle button" src="${v.asset("external/switch.svg")}" width="22" height="22"></em> to <em>Allow ${View.capitalize(v.platform.browser())} notifications</em>.</li>
      </ol>
    @elseif(v.platform.edge() && v.platform.desktop())
      <ol>
        <li>Click <em>Start</em>, then <em>Settings</em>.</li>
        <li>Go to <em>System &gt; Notification</em>.</li>
        <li>Click <em><img alt="the toggle button" src="${v.asset("external/switch.svg")}" width="22" height="22"></em> <em>ON</em> for Campfire.</li>
      </ol>
    @elseif((v.platform.firefox() || v.platform.chrome()) && v.platform.desktop())
      <ol>
        @if(v.platform.windows())
          <li>Click <em>Start</em>, then <em>Settings</em>.</li>
          <li>Go to <em>System &gt; Notification</em>.</li>
          <li>Click <em><img alt="the toggle button" src="${v.asset("external/switch.svg")}" width="22" height="22"></em> <em>ON</em> for Campfire.</li>
        @else
          <li>Click <em aria-label="the Apple menu"></em> in the top left.</li>
          <li>Click <em>System Settings…</em>.</li>
          <li>Click <em>Notifications</em>.</li>
          <li>Click <em>Campfire</em>.</li>
          <li>Click <em><img alt="the allow notifications switch" src="${v.asset("external/switch.svg")}" width="22" height="22"></em> to <em>Allow notifications</em>.</li>
        @endif
      </ol>
    @elseif(v.platform.safari() && v.platform.desktop())
      <ol>
        <li>Click <em aria-label="the Apple menu"></em> in the top left.</li>
        <li>Click <em>System Settings…</em>.</li>
        <li>Click <em>Notifications</em>.</li>
        <li>Click <em>Campfire</em>.</li>
        <li>Click <em><img alt="the allow notifications switch" src="${v.asset("external/switch.svg")}" width="22" height="22"></em> to <em>Allow notifications</em>.</li>
      </ol>
    @elseif((v.platform.safari() || v.platform.chrome()) && v.platform.ios())
      <ol>
        <li>Open the <em><img aria-hidden="true" src="${v.asset("external/gear.svg")}" width="20" height="20"></em> Settings app.</li>
        <li>Scroll to and tap <em>Campfire</em>.</li>
        <li>Tap <em>Notifications</em>.</li>
        <li>Tap <em><img alt="the allow notifications switch button" src="${v.asset("external/switch.svg")}" width="22" height="22"></em> to <em>Allow Notifications</em>.</li>
      </ol>
    @elseif(v.platform.chrome() && v.platform.android())
      <ol>
        <li>Open the <em><img aria-hidden="true" src="${v.asset("external/gear.svg")}" width="20" height="20"></em> Settings app.</li>
        <li>Tap <em>Notifications</em>.</li>
        <li>Tap <em>App notifications</em>.</li>
        <li>Scroll to <em>Campfire</em>.</li>
        <li>Tap <em><img alt="the switch" src="${v.asset("external/switch.svg")}" width="22" height="22"></em> to <em>Allow Notifications</em>.</li>
      </ol>
    @else
      <p>Ensure notifications are allowed for ${View.capitalize(v.platform.browser())} in your system settings.</p>
    @endif
</details>
