@import campfire.messages.*
@import campfire.view.*
@import campfire.web.Paths
@param View v
@param MessageView message
@param String editableBodyHtml
<turbo-frame id="${message.domId("edit")}">
  <div class="message__body position-relative" data-controller="scroll-into-view">
    <div class="message__body-content message__body-content--editing gap">@if(message.attachment() != null)
        $unsafe{MessagePresentation.attachment(v, message.attachment())}

        <div class="message__edit-btns flex align-center justify-space-between gap full-width pad-block-start-half">
          <button name="button" type="submit" class="btn btn--negative center margin-block-end" form="${message.domId("delete_form")}" data-turbo-confirm="Are you sure you want to delete this message?">
            <img aria-hidden="true" src="${v.asset("trash.svg")}" />
            <span class="for-screen-reader">Delete message</span>
</button>        </div>@else
        <div class="composer--edit composer--rich-text">
          <form id="${message.domId("form")}" data-controller="form" data-action="lexxy:file-accept-&gt;form#preventAttachment keydown.esc-&gt;form#cancel keydown.ctrl+enter-&gt;form#submit:prevent keydown.meta+enter-&gt;form#submit:prevent" action="${message.path()}" accept-charset="UTF-8" method="post"><input type="hidden" name="_method" value="patch" />
            <div class="full-width input input--actor min-width fill-white">
              <lexxy-editor rows="1" class="input lexxy-content" aria-multiline="true" aria-label="Edit message" autofocus="autofocus" permitted-attachment-types="application/vnd.campfire.mention application/vnd.actiontext.opengraph-embed" data-action="lexxy:change-&gt;typing-notifications#start keydown-&gt;composer#submitByKeyboard:capture" data-direct-upload-url="${v.url("/rails/active_storage/direct_uploads")}" data-blob-url-template="${v.url("/rails/active_storage/blobs/redirect/:signed_id/:filename")}" id="message_body" input="message_body_trix_input_${message.domId("")}" name="message[body]" value="${editableBodyHtml}">
                <lexxy-prompt trigger="@" name="mention" src="${Paths.autocompletableUsers(message.roomId())}" remote-filtering="true" empty-results="No matches"></lexxy-prompt>
</lexxy-editor>            </div>

            <a data-form-target="cancel" hidden="hidden" href="${message.path()}">Close editor and discard changes</a>

            <div class="message__edit-btns flex align-center justify-space-between gap full-width pad-block-start-half">
              <button name="button" type="submit" class="btn btn--reversed">
                <img aria-hidden="true" src="${v.asset("check.svg")}" />
                <span class="for-screen-reader">Save changes</span>
</button>
              <button name="button" type="submit" class="btn btn--negative" form="${message.domId("delete_form")}" data-turbo-confirm="Are you sure you want to delete this message?">
                <img aria-hidden="true" src="${v.asset("trash.svg")}" />
                <span class="for-screen-reader">Delete message</span>
</button>            </div>
</form>        </div>@endif
    </div>

    <div class="message__actions flex flex-wrap">
      <a class="message__action-btn message__edit-close-btn txt-small btn btn--borderless" href="${message.path()}">
        <img class="colorize--black" aria-hidden="true" src="${v.asset("remove.svg")}" />
        <span class="for-screen-reader">Close editor and discard changes</span>
</a>    </div>

    <form id="${message.domId("delete_form")}" data-turbo-frame="${message.domId("edit")}" action="${message.path()}" accept-charset="UTF-8" method="post"><input type="hidden" name="_method" value="delete" />
  </div>
</turbo-frame>