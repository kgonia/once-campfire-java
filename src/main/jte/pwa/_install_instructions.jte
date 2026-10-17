@import campfire.view.*
@param View v
@if(!(v.platform.chrome() || (v.platform.firefox() && !v.platform.android())))
  <details class="notifications-help pwa__instructions hide-in-pwa" data-controller="pwa-install" data-pwa-install-prompting-class="pwa--can-install" data-notifications-target="details">
    <summary class="btn">
      <img aria-hidden="true" src="${v.asset("external/install.svg")}" width="20" height="20">
      <strong>Install Campfire as a web app.</strong>
      <img aria-hidden="true" class="disclosure" src="${v.asset("disclosure.svg")}" width="10" height="10">
    </summary>

      @if(v.platform.edge())
        <ol>
          <li>Click <em><img alt="the app available - install Campfire chat button" src="${v.asset("install-edge.svg")}" width="16" height="16"></em>in the address bar.</li>
          <li>Click <em>Install</em>.</li>
        </ol>
      @elseif(v.platform.chrome() && v.platform.android())
        <ol>
          <li>Tap the <em><img alt="More options" src="${v.asset("menu-dots-vertical.svg")}" width="16" height="16"></em> menu button.</li>
          <li>Tap <em>Install app</em> in the menu.</li>
        </ol>
      @elseif(v.platform.firefox() && v.platform.android())
        <ol>
          <li>Tap the <em><img alt="More options" src="${v.asset("menu-dots-vertical.svg")}" width="16" height="16"></em> menu button.</li>
          <li>Tap <em>Install</em> in the menu.</li>
        </ol>
      @elseif(v.platform.safari() && v.platform.desktop())
        <ol>
          <li>Click <em>File</em> in the top left.</li>
          <li>Click <em>Add to Dock…</em>.</li>
        </ol>
      @elseif((v.platform.safari() || v.platform.chrome()) && v.platform.ios())
        <p>To receive push notifications in ${View.capitalize(v.platform.browser())} for ${v.platform.operatingSystem()}, you must install Campfire as a web app.</p>
        <ol>
          <li>Tap <em><img alt="the share button" src="${v.asset("external/share.svg")}" width="20" height="20"></em></li>
          <li>Tap <em>Add to Home Screen</em>.</li>
        </ol>
      @else
        <p>Some platforms require you to install Campfire as a web app to receive push notifications.</p>
      @endif
    <div class="margin-block-start txt-align-center pwa__installer">
      <hr class="separator margin-block">
      <button class="btn btn--reversed center" data-action="pwa-install#promptInstall">
        <img aria-hidden="true" src="${v.asset("external/install.svg")}">
        Install now
      </button>
    </div>
  </details>
@endif