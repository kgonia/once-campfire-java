@import campfire.bots.BotAdminService.Listed
@import campfire.rooms.Room
@import campfire.view.*
@import campfire.web.Paths
@param View v
@param Listed bot
<li class="flex flex-column gap flush fill-shade border-radius pad-block pad-inline-double">
  <div class="flex align-center gap">
    <figure class="avatar flex-item--no-shrink" style="--avatar-size: 2.65em;">
      ${v.avatarTag(bot.bot(), Attrs.of().set("loading", "lazy"))}
    </figure>

    <div class="min-width">
      <div class="overflow-ellipsis txt-large"><strong>${bot.bot().name()}</strong></div>
    </div>

    ${View.linkTo(Paths.editAccountBot(bot.bot().id()), Attrs.of().set("class", "btn flex-item-justify-end").set("style", "view-transition-name: chat-bot-" + bot.bot().id()), @`
      ${v.imageTag("pencil.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
      <span class="for-screen-reader">Edit ${bot.bot().name()}</span>
`)}  </div>

@for(Room room : bot.rooms())!{String messagesUrl = v.url(Paths.roomBotMessages(room.id(), bot.bot().botKey()));}!{String curlTextLine = View.curlTextLine(messagesUrl);}!{String curlUploadLine = View.curlUploadLine(messagesUrl);}${Safe.from(@`    <fieldset class="gap max-width pad border border-radius">
      <legend class="min-width txt-align-start pad-inline">
        <strong class="overflow-ellipsis">${room.name()}</strong>
      </legend>

      <div class="flex align-center gap">
        ${v.imageTag("messages-outlined.svg", Attrs.of().aria("hidden", "true").set("size", 24).set("class", "colorize--black"))}

        <div class="flex-item-grow">
          <input type="text" class="input full-width fill-white" value="${curlTextLine}" aria-label="curl command for posting messages" readonly>
        </div>

        <div class="txt-small">
          ${View.buttonToCopyToClipboard(curlTextLine, @`
            ${v.imageTag("copy-paste.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
            <span class="for-screen-reader">Copy message command</span>
`)}        </div>
      </div>

      <div class="flex align-center gap">
        ${v.imageTag("attachment.svg", Attrs.of().aria("hidden", "true").set("size", 24).set("class", "colorize--black"))}

        <div class="flex-item-grow">
          <input type="text" class="input full-width fill-white" value="${curlUploadLine}" aria-label="curl command for posting attachments" readonly>
        </div>

        <div class="txt-small">
          ${View.buttonToCopyToClipboard(curlUploadLine, @`
            ${v.imageTag("copy-paste.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
            <span class="for-screen-reader">Copy attachment command</span>
`)}        </div>
      </div>
    </fieldset>
`)}@endfor</li>
