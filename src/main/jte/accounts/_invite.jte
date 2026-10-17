@import campfire.view.*
@import campfire.web.Paths
@param View v
@param String joinCode
<div class="flex flex-column align-center gap">!{var url = v.url(Paths.join(joinCode));}

  <label class="flex flex-column gap full-width" style="--row-gap: 0.5em">
    <strong id="invite_label" class="invite-label">Share to invite more people</strong>
    <span class="flex align-center gap input input--actor fill-white">
      ${v.imageTag("person-add.svg", Attrs.of().aria("hidden", "true").set("size", 20).set("class", "colorize--black"))}
      <input type="text" class="input" id="invite_url" value="${url}" aria-labelledby="invite_label" readonly>
    </span>
  </label>

  <div class="flex align-center gap">
    ${View.linkToZoomQrCode(url, @`
      <span class="for-screen-reader">Show join link QR code</span>
      ${v.imageTag("qr-code.svg", Attrs.of().aria("hidden", "true").set("size", 20).set("class", "colorize--black"))}
`)}
    ${View.buttonToCopyToClipboard(url, @`
      <span class="for-screen-reader">Copy join link</span>
      ${v.imageTag("copy-paste.svg", Attrs.of().aria("hidden", "true").set("size", 20).set("class", "colorize--black"))}
`)}
    ${View.webShareSessionButton(url, "Link to join Campfire", "Hit this link to join me in Campfire and start chatting.", @`
      <span class="for-screen-reader">Share join link</span>
      ${v.imageTag("share.svg", Attrs.of().aria("hidden", "true").set("size", 20).set("class", "colorize--black"))}
`)}
@if(v.canAdminister())      ${View.buttonTo(Paths.accountJoinCode(), Attrs.of().set("class", "btn btn--regenerate"), @`
        ${v.imageTag("refresh.svg", Attrs.of().aria("hidden", "true").set("size", 20).set("class", "colorize--black"))}
        <span class="for-screen-reader">Regenerate join link</span>
`)}@endif  </div>
</div>
