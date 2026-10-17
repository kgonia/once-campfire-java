@import campfire.rooms.*
@import campfire.view.*
@import campfire.web.Paths
@param View v
@param RoomView room
<div class="composer flex align-end gap position-relative"
      data-controller="typing-notifications" data-typing-notifications-active-class="typing-indicator--active">
    <a class="btn flex-item-no-shrink margin-block-end composer__context-btn" style="view-transition-name: input-switcher" href="${Paths.searches()}">
      <img aria-hidden="true" src="${v.asset("search.svg")}" width="20" height="20" />
      <span class="for-screen-reader">Search</span>
</a>
    <turbo-frame id="composer-frame">
      <form id="composer" class="margin-block flex-item-grow contain" data-controller="composer drop-target" data-action="dragenter-&gt;drop-target#dragenter dragover-&gt;drop-target#dragover drop-&gt;drop-target#drop drop-target:drop@window-&gt;composer#dropFiles lexxy:file-accept-&gt;composer#preventAttachment refresh-room:online@window-&gt;composer#online typing-notifications#stop paste-&gt;composer#pasteFiles turbo:submit-end-&gt;composer#submitEnd refresh-room:offline@window-&gt;composer#offline" data-composer-messages-outlet="#message-area" data-composer-toolbar-class="composer--rich-text" data-composer-room-id-value="${room.id()}" action="${Paths.roomMessages(room.id())}" accept-charset="UTF-8" method="post">
        <fieldset data-composer-target="fields" contents>
          <div class="flex flex-column">
            <div class="composer__filelist flex flex--align-center gap flex-wrap" data-composer-target="fileList"></div>

            <div class="flex composer__input input input--actor fill-white min-width" style="--input-border-radius: 1.3rem">
              <div class="flex align-end gap full-width">
                <img aria-hidden="true" class="composer__input-hint colorize--black" style="view-transition-name: input-btn;" src="${v.asset("messages-outlined.svg")}" width="22" height="22" />

                <div class="flex flex-column flex-item-grow min-width gap">
                  <lexxy-editor rows="1" class="input lexxy-content" style="order: -1" aria-multiline="true" aria-label="Write a message" permitted-attachment-types="application/vnd.campfire.mention application/vnd.actiontext.opengraph-embed" data-controller="unfurl" data-action="lexxy:change-&gt;typing-notifications#start keydown-&gt;composer#submitByKeyboard:capture lexxy:change-&gt;composer#saveDraft lexxy:insert-link-&gt;unfurl#unfurl" data-composer-target="text" data-direct-upload-url="${v.url("/rails/active_storage/direct_uploads")}" data-blob-url-template="${v.url("/rails/active_storage/blobs/redirect/:signed_id/:filename")}" id="message_body" input="message_body_trix_input_message" name="message[body]">
                    <lexxy-prompt trigger="@" name="mention" src="${Paths.autocompletableUsers(room.id())}" remote-filtering="true" empty-results="No matches"></lexxy-prompt>
</lexxy-editor>                </div>

                <label class="btn btn--borderless txt-small flex-item-no-shrink composer__attachment-btn input--file">
                  <img class="colorize--black" aria-hidden="true" src="${v.asset("attachment.svg")}" width="22" height="22" />
                  <input type="file" data-action="composer#filePicked" multiple />
                  <span class="for-screen-reader">Attach a file</span>
                </label>

                <button class="btn btn--borderless txt-small flex-item-no-shrink composer__rich-text-btn" type="button" data-action="composer#toggleToolbar">
                  <img class="colorize--black" aria-hidden="true" src="${v.asset("text-options.svg")}" width="20" height="20" />
                  <span class="for-screen-reader">Rich text</span>
                </button>

                <button name="send" type="submit" data-action="composer#submit" class="btn btn--reversed flex-item-no-shrink txt-small">
                  <img aria-hidden="true" src="${v.asset("arrow-up.svg")}" width="20" height="20" />
                  <span class="for-screen-reader">Send Message</span>
</button>              </div>
            </div>
          </div>
        </fieldset>

        <div class="typing-indicator gap txt-small align-center flex-inline" data-typing-notifications-target="indicator">
          <div class="typing-indicator__author spinner" data-typing-notifications-target="author"></div>
        </div>

        <input data-composer-target="clientid" type="hidden" name="message[client_message_id]" id="message_client_message_id" />
</form>    </turbo-frame>
  </div>