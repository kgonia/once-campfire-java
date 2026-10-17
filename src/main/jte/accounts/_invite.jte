@import campfire.web.Paths
@param campfire.view.View v
@param String joinCode
!{var url = v.url(Paths.join(joinCode));}
<div class="flex flex-column align-center gap">
  <label class="flex flex-column gap full-width" style="--row-gap: 0.5em">
    <strong id="invite_label" class="invite-label">Share to invite more people</strong>
    <span class="flex align-center gap input input--actor fill-white">
      <img aria-hidden="true" class="colorize--black" src="${v.asset("person-add.svg")}" width="20" height="20">
      <input type="text" class="input" id="invite_url" value="${url}" aria-labelledby="invite_label" readonly>
    </span>
  </label>

  <div class="flex align-center gap">
    @template.helpers._qr_code_link(url = url, content = @`
      <span class="for-screen-reader">Show join link QR code</span>
      <img aria-hidden="true" class="colorize--black" src="${v.asset("qr-code.svg")}" width="20" height="20">
    `)
    @template.helpers._copy_to_clipboard_button(url = url, content = @`
      <span class="for-screen-reader">Copy join link</span>
      <img aria-hidden="true" class="colorize--black" src="${v.asset("copy-paste.svg")}" width="20" height="20">
    `)
    @template.helpers._web_share_button(url = url, title = "Link to join Campfire", text = "Hit this link to join me in Campfire and start chatting.", content = @`
      <span class="for-screen-reader">Share join link</span>
      <img aria-hidden="true" class="colorize--black" src="${v.asset("share.svg")}" width="20" height="20">
    `)
    @if(v.canAdminister())
      <form class="button_to" method="post" action="${Paths.accountJoinCode()}">
        <button class="btn btn--regenerate" type="submit">
          <img aria-hidden="true" class="colorize--black" src="${v.asset("refresh.svg")}" width="20" height="20">
          <span class="for-screen-reader">Regenerate join link</span>
        </button>
      </form>
    @endif
  </div>
</div>
