@import campfire.accounts.AccountPage
@import campfire.users.User
@import campfire.view.*
@import campfire.web.Paths
@param View v
@param AccountPage page
!{boolean restricted = page.account().restrictRoomCreationToAdministrators();}!{var logoForm = View.formWith(page.action()).model("account").method("patch").cssClass("txt--medium").data("controller", "form");}!{var previewForm = View.formWith(page.action()).model("account").method("patch").data("controller", "form");}!{var nameForm = View.formWith(page.action()).model("account").method("patch").data("controller", "form").cssClass("flex flex-column gap");}!{var settingsForm = View.formWith(page.action()).model("account").method("put").data("controller", "form").cssClass("flex align-center gap center");}@template.layouts.application(v = v, title = "Account settings",
  nav = @`  <div class="flex-item-justify-start">
    ${v.linkBackToLastRoomVisited()}
  </div>

@if(v.canAdminister())${Safe.from(@`    <div class="flex align-center gap flex-item-justify-end">
      ${View.linkTo(Paths.accountBots(), Attrs.of().set("class", "btn").set("style", "view-transition-name: chat-bots"), @`
        ${v.imageTag("bot.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
        <span class="for-screen-reader">Set up chat bots</span>
`)}
      ${View.linkTo(Paths.editAccountCustomStyles(), Attrs.of().set("class", "btn").set("style", "view-transition-name: custom-styles"), @`
        ${v.imageTag("art.svg", Attrs.of().set("size", 20).aria("hidden", "true"))}
        <span class="for-screen-reader">Custom styles</span>
`)}    </div>
`)}@endif`,
  content = @`<section class="panel txt-align-center flex flex-column gap" style="view-transition-name: account-settings">
@if(v.canAdminister())${Safe.from(@`    <div class="align-center center avatar__form gap" data-controller="upload-preview">
      ${logoForm.wrap(Safe.from(@`
        <label class="btn input--file">
          ${v.imageTag("camera.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
          ${logoForm.fileField("logo", Attrs.of().set("class", "input").set("accept", "image/*").data("action", "upload-preview#previewImage change->form#submit"))}
          <span class="for-screen-reader">Upload logo</span>
        </label>
`))}
      ${previewForm.wrap(Safe.from(@`
        <label class="btn avatar input--file account-logo txt-xx-large">
          ${v.imageTag(v.freshAccountLogoPath(), Attrs.of().set("role", "presentation").set("size", 48).data("upload_preview_target", "image"))}
          ${previewForm.fileField("logo", Attrs.of().set("class", "input").set("accept", "image/*").data("action", "upload-preview#previewImage change->form#submit"))}
          <span class="for-screen-reader">Upload logo</span>
        </label>
`))}
${v.accountHasLogo ? Safe.concat(Safe.of("        "), View.buttonTo(v.freshAccountLogoPath(), "delete", Attrs.of().set("class", "btn btn--negative txt-small avatar__delete-btn"), Safe.from(@`
          ${v.imageTag("minus.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
          <span class="for-screen-reader">Delete logo</span>
`))) : Safe.of("")}    </div>

    ${nameForm.wrap(Safe.from(@`
      <div class="flex align-center gap">
        ${v.translationButton("account_name")}

        <label class="flex align-center gap flex-item-grow">
          ${nameForm.textField("name", page.account().name(), Attrs.of().set("class", "input txt-large").set("autocomplete", "off").set("placeholder", "Name this account").set("autofocus", true).data("action", "keydown.enter->form#submit"))}
        </label>

        ${nameForm.button(Attrs.of().set("class", "btn btn--reversed center").set("type", "submit"), Safe.from(@`
          ${v.imageTag("check.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
          <span class="for-screen-reader">Save changes</span>
`))}      </div>
`))}
    <div class="margin-block-start pad-block pad-inline-double fill-shade border-radius">
      ${settingsForm.wrap(Safe.from(@`
        <div class="flex-item-grow flex align-center gap txt-align-start">
          ${v.imageTag("crown.svg", Attrs.of().set("class", "colorize--black").aria("hidden", "true").set("size", 18))} Must be admin to create new rooms
        </div>
          ${settingsForm.fieldsFor("settings").hiddenField("restrict_room_creation_to_administrators", null, Attrs.of().set("value", String.valueOf(!restricted)))}

        <label class="switch">
          <input type="checkbox"
                class="switch__input"
                ${restricted ? "checked" : ""}
                data-action="change->form#submit">
          <span class="switch__btn round"></span>
          <span class="for-screen-reader">
            Must be admin to create new rooms
          </span>
        </label>

`))}    </div>
`)}@else${Safe.from(@`    ${v.accountLogoTag("txt-xx-large center")}
    <h1 class="flex-item-grow txt-x-large">${page.account().name()}</h1>
`)}@endif
  <div class="margin-block pad-inline pad-block-start fill-shade border-radius">
    @template.accounts._invite(v = v, joinCode = page.account().joinCode())

    <hr class="margin-block separator full-width" style="--border-style: solid">

    <menu class="flex flex-column gap margin-none pad">
      <turbo-frame id="account_users">
        @for(User user : page.administrators())@template.accounts.users._user(v = v, user = user)@endfor
@if(!page.administrators().isEmpty() && !page.members().isEmpty())${Safe.from(@`          <hr class="separator full-width" style="--border-style: solid">
`)}@endif
        @for(User user : page.members())@template.accounts.users._user(v = v, user = user)@endfor        @if(page.nextPage() != null)@template.accounts.users._next_page_container(page = page.nextPage())@endif
      </turbo-frame>
    </menu>
  </div>
</section>
`,
  footer = @`  <div class="txt-align-center center margin-block-double txt-subtle">Campfire&trade; version ${v.versionBadge()}</div>
`)