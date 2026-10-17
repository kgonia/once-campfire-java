@import campfire.web.Paths
@param campfire.view.View v
@param campfire.users.User user
@param String transferId
!{String url = v.url(Paths.sessionTransfer(transferId));}
<fieldset>
  <legend class="gap">
    <img aria-hidden="true" class="colorize--black" src="${v.asset("laptop.svg")}" width="36" height="36">
    <img aria-hidden="true" class="colorize--black" src="${v.asset("transfer.svg")}" width="36" height="36">
    <img aria-hidden="true" class="colorize--black" src="${v.asset("mobile-phone.svg")}" width="36" height="36">
  </legend>

  <div class="flex flex-column gap">
    @if(!v.isCurrentUser(user.id()))
      <div class="flex align-center gap justify-center">
        <img aria-hidden="true" class="flex-item-no-shrink colorize--black" src="${v.asset("crown.svg")}" width="16" height="16">
        <label for="session_transfer_url">Share to get them back into their account</label>
      </div>
    @else
      <label for="session_transfer_url" class="for-screen-reader">Use this link to login automatically on another device</label>
    @endif

    <input type="text" class="input" value="${url}" id="session_transfer_url" readonly>

    <div class="flex align-center center gap">
      @template.helpers._qr_code_link(url = url, content = @`
        <span class="for-screen-reader">Show auto-login QR code</span>
        <img aria-hidden="true" class="colorize--black" src="${v.asset("qr-code.svg")}" width="20" height="20">
      `)
      @template.helpers._copy_to_clipboard_button(url = url, content = @`
        <span class="for-screen-reader">Copy auto-login link</span>
        <img aria-hidden="true" class="flex-item-no-shrink colorize--black" src="${v.asset("copy-paste.svg")}" width="20" height="20">
      `)
      @template.helpers._web_share_button(url = url, title = "Your sign-in link", text = "This is your own private sign-in URL, DO NOT SHARE IT. Use it to sign-in on another device or if you get locked out.", content = @`
        <span class="for-screen-reader">Share auto-login link</span>
        <img aria-hidden="true" class="flex-item-no-shrink colorize--black" src="${v.asset("share.svg")}" width="20" height="20">
      `)
    </div>
  </div>
</fieldset>
