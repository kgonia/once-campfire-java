@import campfire.view.*
@import campfire.web.Paths
@param View v
@param campfire.users.User user
@param String transferId
!{String url = v.url(Paths.sessionTransfer(transferId));}
<fieldset>
  <legend class="gap">
    ${v.imageTag("laptop.svg", Attrs.of().aria("hidden", "true").set("size", 36).set("class", "colorize--black"))}
    ${v.imageTag("transfer.svg", Attrs.of().aria("hidden", "true").set("size", 36).set("class", "colorize--black"))}
    ${v.imageTag("mobile-phone.svg", Attrs.of().aria("hidden", "true").set("size", 36).set("class", "colorize--black"))}
  </legend>


  <div class="flex flex-column gap">
@if(!v.isCurrentUser(user.id()))      <div class="flex align-center gap justify-center">
        ${v.imageTag("crown.svg", Attrs.of("size", 16).aria("hidden", "true").set("class", "flex-item-no-shrink colorize--black"))}
        <label for="session_transfer_url">Share to get them back into their account</label>
      </div>
@else      <label for="session_transfer_url" class="for-screen-reader">Use this link to login automatically on another device</label>
@endif
    <input type="text" class="input" value="${url}" id="session_transfer_url" readonly>

    <div class="flex align-center center gap">
      ${View.linkToZoomQrCode(url, @`
        <span class="for-screen-reader">Show auto-login QR code</span>
        ${v.imageTag("qr-code.svg", Attrs.of().aria("hidden", "true").set("size", 20).set("class", "colorize--black"))}
`)}
      ${View.buttonToCopyToClipboard(url, @`
        <span class="for-screen-reader">Copy auto-login link</span>
        ${v.imageTag("copy-paste.svg", Attrs.of().aria("hidden", "true").set("size", 20).set("class", "flex-item-no-shrink colorize--black"))}
`)}
      ${View.webShareSessionButton(url, "Your sign-in link", "This is your own private sign-in URL, DO NOT SHARE IT. Use it to sign-in on another device or if you get locked out.", @`
        <span class="for-screen-reader">Share auto-login link</span>
        ${v.imageTag("share.svg", Attrs.of().aria("hidden", "true").set("size", 20).set("class", "flex-item-no-shrink colorize--black"))}
`)}    </div>
  </div>
</fieldset>
