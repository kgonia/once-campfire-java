@import campfire.accounts.HelpContact
@import campfire.view.*
@import campfire.web.Paths
@param View v
@param String joinCode
@param HelpContact helpContact
!{var form = View.formWith(Paths.join(joinCode)).model("user").cssClass("center");}@template.layouts.application(v = v, title = "Sign up", bodyClass = "signup",
  nav = @`  <div class="flex-item-justify-end">
    ${View.linkTo(Paths.NEW_SESSION, Attrs.of("class", "btn flex-item-justify-end"), @`
      ${v.imageTag("login-keys.svg", Attrs.of().aria("hidden", "true"))}
      <span class="for-screen-reader">Sign in</span>
`)}  </div>
`,
  content = @`${form.wrap(Safe.from(@`
  <section class="nametag u-relative">
    <div class="flex justify-center align-center pad-block">
      ${v.imageTag("lanyard.svg", Attrs.of("class", "nametag__lanyard").aria("hidden", "true"))}
    </div>

    <div class="nametag__inner flex flex-column gap">
      <fieldset class="flex flex-column center-block">
        <legend class="txt-align-center flex gap">
          ${v.accountLogoTag(null)}
          <strong class="txt-large">${v.account.name()}</strong>
        </legend>

        <label class="align-center center avatar__form gap" data-controller="upload-preview">
          <div class="btn input--file">
            ${v.imageTag("camera.svg", Attrs.of().aria("hidden", "true"))}
            ${form.fileField("avatar", Attrs.of("class", "input", "accept", "image/*").data("upload_preview_target", "input", "action", "upload-preview#previewImage"))}
            <span class="for-screen-reader">Upload avatar</span>
          </div>

          <div class="btn avatar input--file txt-xx-large">
            ${v.imageTag("default-avatar.svg", Attrs.of().aria("hidden", "true").data("upload_preview_target", "image"))}
            <span class="for-screen-reader">Avatar</span>
          </div>
        </label>
      </fieldset>

      <div class="flex align-center gap">
        ${v.translationButton("user_name")}
        <label class="flex align-center gap flex-item-grow txt-large input input--actor">
          ${form.textField("name", null, Attrs.of("class", "input", "autocomplete", "name", "placeholder", "Name", "autofocus", true, "required", true).data("1p-ignore", true))}
          ${v.imageTag("person.svg", Attrs.of().aria("hidden", "true").set("size", 24).set("class", "colorize--black"))}
        </label>
      </div>

      <div class="flex align-center gap">
        ${v.translationButton("email_address")}
        <label class="flex align-center gap flex-item-grow txt-large input input--actor">
          ${form.emailField("email_address", null, Attrs.of("class", "input", "autocomplete", "username", "placeholder", "Email address", "required", true))}
          ${v.imageTag("email.svg", Attrs.of().aria("hidden", "true").set("size", 24).set("class", "colorize--black"))}
        </label>
      </div>

      <div class="flex align-center gap">
        ${v.translationButton("password")}
        <label class="flex align-center gap flex-item-grow txt-large input input--actor">
          ${form.passwordField("password", Attrs.of("class", "input", "autocomplete", "new-password", "placeholder", "Password", "required", true, "maxlength", 72))}
          ${v.imageTag("password.svg", Attrs.of().aria("hidden", "true").set("size", 24).set("class", "colorize--black"))}
        </label>
      </div>

      ${form.button(Attrs.of("class", "btn btn--reversed center txt-large", "type", "submit"), @`
        ${v.imageTag("check.svg", Attrs.of().aria("hidden", "true"))}
        <span class="for-screen-reader">Save</span>
`)}    </div>
  </section>
`))}
@template.accounts._help_contact(v = v, helpContact = helpContact)
`)