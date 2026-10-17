@import campfire.view.*
@import campfire.web.Paths
@param View v
@param String joinCode
<div id="system_welcome" class="message message--formatted txt-align-center center">
    <div class="message__body center">
      <div class="message__body-content position-relative">
        ${v.accountLogoTag("center margin-block-end txt-large")}
        <div class="flex align-center gap">
          <div class="system-welcome--translation">
            ${v.translationButton("invite_message")}
          </div>
          <p>
            <strong>Welcome to Campfire</strong><br>
            To invite people to chat, share the join link below.
          </p>
        </div>
        @template.accounts._invite(v = v, joinCode = joinCode)
      </div>
    </div>
  </div>