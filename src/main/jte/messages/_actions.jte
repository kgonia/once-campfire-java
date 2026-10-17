@import campfire.messages.*
@param campfire.view.View v
@param MessageView message
<div class="message__actions" data-controller="soft-keyboard">
  <details class="position-relative" data-controller="popup" data-action="keydown.esc-&gt;popup#close toggle-&gt;popup#toggle click@document-&gt;popup#closeOnClickOutside" data-popup-orientation-top-class="popup-orientation-top">
    <summary class="btn message__action-btn message__options-btn">
      <img class="colorize--black" aria-hidden="true" src="${v.asset("menu-dots-horizontal.svg")}" width="20" height="20" />
      <span class="for-screen-reader">Message options</span>
    </summary>

    <div class="message__actions-menu border shadow" data-popup-target="menu">
      <div class="quick-boosts">@for(var reaction : BoostView.QUICK_REACTIONS)
          <form data-turbo-frame="${message.domId("boosting")}" data-action="popup#close" action="${message.boostsPath()}" accept-charset="UTF-8" method="post">
            <input type="hidden" name="boost[content]" id="boost_content" value="${reaction.emoji()}" />
            <button name="button" type="submit" title="${reaction.label()}" class="btn message__action-btn" data-emoji="${reaction.emoji()}">
              <figure class="margin-none boost-character">${reaction.emoji()}</figure>
              <span class="for-screen-reader">${reaction.label()}</span>
</button></form>@endfor

        <a class="btn message__action-btn message__boost-btn" data-turbo-frame="${message.domId("new_boost")}" data-action="soft-keyboard#open popup#close" href="${message.newBoostPath()}">
          <img class="colorize--black" aria-hidden="true" src="${v.asset("boost.svg")}" width="20" height="20" />
          <span class="for-screen-reader">New boost</span>
</a>      </div>

      <div class="flex flex-wrap border-top margin-block-start-half pad-block-start-half message__actions-grid">@if(message.attachment() != null)
          <a class="btn message__action-btn center full-width hide-in-ios-pwa" title="Download" aria-label="Download" href="${message.attachment().downloadPath()}">
            <img class="colorize--black" aria-hidden="true" src="${v.asset("download.svg")}" width="20" height="20" />
</a>
          <button class="btn message__action-btn center full-width" data-controller="web-share" data-action="web-share#share" data-web-share-files-value="${message.attachment().blobPath()}" data-web-share-title-value="${message.attachment().filename()}" title="Share" aria-label="Share">
            <img class="colorize--black" aria-hidden="true" src="${v.asset("share.svg")}" width="20" height="20" />
</button>@else
          <button class="btn message__action-btn center full-width" data-action="reply#reply" title="Reply" aria-label="Reply">
            <img class="colorize--black" aria-hidden="true" src="${v.asset("reply.svg")}" width="20" height="20" />
</button>@endif

        <button class="btn message__action-btn center full-width" title="Copy link" aria-label="Copy link" data-controller="copy-to-clipboard" data-action="copy-to-clipboard#copy" data-copy-to-clipboard-success-class="btn--success" data-copy-to-clipboard-url-value="${message.atPath()}">
          <img class="colorize--black" aria-hidden="true" src="${v.asset("link.svg")}" width="20" height="20" />
</button>
        <a class="btn message__action-btn center full-width message__edit-btn" data-turbo-frame="${message.domId("edit")}" title="Edit" aria-label="Edit" href="${message.editPath()}">
          <img class="colorize--black" aria-hidden="true" src="${v.asset("pencil.svg")}" width="20" height="20" />
</a>      </div>
    </div>
</details></div>