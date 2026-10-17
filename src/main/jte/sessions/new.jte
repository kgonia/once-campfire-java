@import campfire.accounts.HelpContact
@import campfire.view.*
@import campfire.web.Paths
@param View v
@param String emailAddress
@param HelpContact helpContact
!{var form = View.formWith(v.url(Paths.session())).cssClass("flex flex-column gap");}@template.layouts.application(v = v, title = "Sign in",
  head = @`${View.turboPageRequiresReloadTag()}`,
  content = @`<section class="txt-align-center">
  <div class="panel @if(v.flashAlert != null)shake@endif">
    ${v.accountLogoTag("center margin-block-end txt-xx-large")}

    ${form.wrap(Safe.from(@`
      <fieldset class="flex flex-column gap center-block upad">
        <legend class="txt-large txt-align-center"><strong>${v.account.name()}</strong></legend>

        <div class="flex align-center gap">
          ${v.translationButton("email_address")}
          <label class="flex align-center gap input input--actor txt-large">
            ${form.emailField("email_address", null, Attrs.of("required", true, "class", "input", "autofocus", true, "autocomplete", "username", "placeholder", "Enter your email address", "value", emailAddress))}
            ${v.imageTag("email.svg", Attrs.of().aria("hidden", "true").set("size", 24).set("class", "colorize--black"))}
          </label>
        </div>

        <div class="flex align-center gap">
          ${v.translationButton("password")}
          <label class="flex align-center gap input input--actor txt-large">
            ${form.passwordField("password", Attrs.of("required", true, "class", "input", "autocomplete", "current-password", "placeholder", "Enter your password", "maxlength", 72))}
            ${v.imageTag("password.svg", Attrs.of().aria("hidden", "true").set("size", 24).set("class", "colorize--black"))}
          </label>
        </div>

        ${form.button(Attrs.of("class", "btn btn--reversed center txt-large", "type", "submit", "name", "log_in"), @`
          ${v.imageTag("arrow-right.svg", Attrs.of().aria("hidden", "true"))}
          <span class="for-screen-reader">Go</span>
`)}      </fieldset>
`))}  </div>

  @template.accounts._help_contact(v = v, helpContact = helpContact)
</section>
`)