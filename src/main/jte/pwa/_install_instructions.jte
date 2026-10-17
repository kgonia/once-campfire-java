@import campfire.view.*
@import campfire.web.Paths
@param View v
@if(!(v.platform.chrome() || (v.platform.firefox() && !v.platform.android())))  <details class="notifications-help pwa__instructions hide-in-pwa" data-controller="pwa-install" data-pwa-install-prompting-class="pwa--can-install" data-notifications-target="details">
    <summary class="btn">
      ${v.imageTag("external/install.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
      <strong>Install Campfire as a web app.</strong>
      ${v.imageTag("disclosure.svg", Attrs.of().aria("hidden", "true").set("size", 10).set("class", "disclosure"))}
    </summary>

@if(v.platform.edge())        <ol>
          <li>Click <em>${v.imageTag("install-edge.svg", Attrs.of().set("alt", "the app available - install Campfire chat button").set("size", 16))}</em>in the address bar.</li>
          <li>Click <em>Install</em>.</li>
        </ol>
@elseif(v.platform.chrome() && v.platform.android())        <ol>
          <li>Tap the <em>${v.imageTag("menu-dots-vertical.svg", Attrs.of().set("alt", "More options").set("size", 16))}</em> menu button.</li>
          <li>Tap <em>Install app</em> in the menu.</li>
        </ol>
@elseif(v.platform.firefox() && v.platform.android())        <ol>
          <li>Tap the <em>${v.imageTag("menu-dots-vertical.svg", Attrs.of().set("alt", "More options").set("size", 16))}</em> menu button.</li>
          <li>Tap <em>Install</em> in the menu.</li>
        </ol>
@elseif(v.platform.safari() && v.platform.desktop())        <ol>
          <li>Click <em>File</em> in the top left.</li>
          <li>Click <em>Add to Dock…</em>.</li>
        </ol>
@elseif((v.platform.safari() || v.platform.chrome()) && v.platform.ios())        <p>To receive push notifications in ${View.capitalize(v.platform.browser())} for ${v.platform.operatingSystem()}, you must install Campfire as a web app.</p>
        <ol>
          <li>Tap <em>${v.imageTag("external/share.svg", Attrs.of().set("alt", "the share button").set("size", 20))}</em></li>
          <li>Tap <em>Add to Home Screen</em>.</li>
        </ol>
@else        <p>Some platforms require you to install Campfire as a web app to receive push notifications.</p>
@endif
    <div class="margin-block-start txt-align-center pwa__installer">
      <hr class="separator margin-block">
      <button class="btn btn--reversed center" data-action="pwa-install#promptInstall">
        ${v.imageTag("external/install.svg", Attrs.of().aria("hidden", "true"))}
        Install now
      </button>
    </div>
  </details>
@endif