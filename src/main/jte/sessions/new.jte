@import campfire.accounts.HelpContact
@import campfire.web.Paths
@param campfire.view.View v
@param String emailAddress
@param HelpContact helpContact
@template.layouts.application(v = v, title = "Sign in", head = @`
  <meta name="turbo-visit-control" content="reload">
`, content = @`
  <section class="txt-align-center">
    <div class="panel ${v.flashAlert != null ? "shake" : ""}">
      @template.helpers._account_logo(v = v, style = "center margin-block-end txt-xx-large")

      <form class="flex flex-column gap" action="${v.url(Paths.session())}" accept-charset="UTF-8" method="post">
        <fieldset class="flex flex-column gap center-block upad">
          <legend class="txt-large txt-align-center"><strong>${v.account.name()}</strong></legend>

          <div class="flex align-center gap">
            @template.helpers._translation_button(v = v, key = "email_address")
            <label class="flex align-center gap input input--actor txt-large">
              <input required class="input" autofocus autocomplete="username" placeholder="Enter your email address" value="${emailAddress}" type="email" name="email_address" id="email_address">
              <img aria-hidden="true" class="colorize--black" src="${v.asset("email.svg")}" width="24" height="24">
            </label>
          </div>

          <div class="flex align-center gap">
            @template.helpers._translation_button(v = v, key = "password")
            <label class="flex align-center gap input input--actor txt-large">
              <input required class="input" autocomplete="current-password" placeholder="Enter your password" maxlength="72" size="72" type="password" name="password" id="password">
              <img aria-hidden="true" class="colorize--black" src="${v.asset("password.svg")}" width="24" height="24">
            </label>
          </div>

          <button name="log_in" type="submit" class="btn btn--reversed center txt-large">
            <img aria-hidden="true" src="${v.asset("arrow-right.svg")}">
            <span class="for-screen-reader">Go</span>
          </button>
        </fieldset>
      </form>
    </div>

    @template.accounts._help_contact(v = v, helpContact = helpContact)
  </section>
`)
