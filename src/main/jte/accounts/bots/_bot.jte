@import campfire.bots.BotAdminService.Listed
@import campfire.rooms.Room
@import campfire.view.View
@import campfire.web.Paths
@param View v
@param Listed bot
<li class="flex flex-column gap flush fill-shade border-radius pad-block pad-inline-double">
  <div class="flex align-center gap">
    <figure class="avatar flex-item--no-shrink" style="--avatar-size: 2.65em;">
      @template.users._avatar(v = v, user = bot.bot(), lazy = true)
    </figure>

    <div class="min-width">
      <div class="overflow-ellipsis txt-large"><strong>${bot.bot().name()}</strong></div>
    </div>

    <a class="btn flex-item-justify-end" style="view-transition-name: chat-bot-${bot.bot().id()}" href="${Paths.editAccountBot(bot.bot().id())}">
      <img aria-hidden="true" src="${v.asset("pencil.svg")}" width="20" height="20">
      <span class="for-screen-reader">Edit ${bot.bot().name()}</span>
    </a>
  </div>

  @for(Room room : bot.rooms())
    !{String messagesUrl = v.url(Paths.roomBotMessages(room.id(), bot.bot().botKey()));}
    !{String curlTextLine = View.curlTextLine(messagesUrl);}
    !{String curlUploadLine = View.curlUploadLine(messagesUrl);}
    <fieldset class="gap max-width pad border border-radius">
      <legend class="min-width txt-align-start pad-inline">
        <strong class="overflow-ellipsis">${room.name()}</strong>
      </legend>

      <div class="flex align-center gap">
        <img aria-hidden="true" class="colorize--black" src="${v.asset("messages-outlined.svg")}" width="24" height="24">

        <div class="flex-item-grow">
          <input type="text" class="input full-width fill-white" value="${curlTextLine}" aria-label="curl command for posting messages" readonly>
        </div>

        <div class="txt-small">
          @template.helpers._copy_to_clipboard_button(url = curlTextLine, content = @`
            <img aria-hidden="true" src="${v.asset("copy-paste.svg")}" width="20" height="20">
            <span class="for-screen-reader">Copy message command</span>
          `)
        </div>
      </div>

      <div class="flex align-center gap">
        <img aria-hidden="true" class="colorize--black" src="${v.asset("attachment.svg")}" width="24" height="24">

        <div class="flex-item-grow">
          <input type="text" class="input full-width fill-white" value="${curlUploadLine}" aria-label="curl command for posting attachments" readonly>
        </div>

        <div class="txt-small">
          @template.helpers._copy_to_clipboard_button(url = curlUploadLine, content = @`
            <img aria-hidden="true" src="${v.asset("copy-paste.svg")}" width="20" height="20">
            <span class="for-screen-reader">Copy attachment command</span>
          `)
        </div>
      </div>
    </fieldset>
  @endfor
</li>
