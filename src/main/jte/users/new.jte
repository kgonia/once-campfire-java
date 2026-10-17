@import campfire.accounts.HelpContact
@import campfire.web.Paths
@param campfire.view.View v
@param String joinCode
@param HelpContact helpContact
@template.layouts.application(v = v, title = "Sign up", bodyClass = "signup", nav = @`
  <div class="flex-item-justify-end">
    <a class="btn flex-item-justify-end" href="${Paths.NEW_SESSION}">
      <img aria-hidden="true" src="${v.asset("login-keys.svg")}">
      <span class="for-screen-reader">Sign in</span>
    </a>
  </div>
`, content = @`
  <form class="center" enctype="multipart/form-data" action="${Paths.join(joinCode)}" accept-charset="UTF-8" method="post">
    <section class="nametag u-relative">
      <div class="flex justify-center align-center pad-block">
        <img class="nametag__lanyard" aria-hidden="true" src="${v.asset("lanyard.svg")}">
      </div>

      <div class="nametag__inner flex flex-column gap">
        <fieldset class="flex flex-column center-block">
          <legend class="txt-align-center flex gap">
            @template.helpers._account_logo(v = v)
            <strong class="txt-large">${v.account.name()}</strong>
          </legend>

          <label class="align-center center avatar__form gap" data-controller="upload-preview">
            <div class="btn input--file">
              <img aria-hidden="true" src="${v.asset("camera.svg")}">
              <input class="input" accept="image/*" data-upload-preview-target="input" data-action="upload-preview#previewImage" type="file" name="user[avatar]" id="user_avatar">
              <span class="for-screen-reader">Upload avatar</span>
            </div>

            <div class="btn avatar input--file txt-xx-large">
              <img aria-hidden="true" data-upload-preview-target="image" src="${v.asset("default-avatar.svg")}">
              <span class="for-screen-reader">Avatar</span>
            </div>
          </label>
        </fieldset>

        <div class="flex align-center gap">
          @template.helpers._translation_button(v = v, key = "user_name")
          <label class="flex align-center gap flex-item-grow txt-large input input--actor">
            <input class="input" autocomplete="name" placeholder="Name" autofocus required data-1p-ignore="true" type="text" name="user[name]" id="user_name">
            <img aria-hidden="true" class="colorize--black" src="${v.asset("person.svg")}" width="24" height="24">
          </label>
        </div>

        <div class="flex align-center gap">
          @template.helpers._translation_button(v = v, key = "email_address")
          <label class="flex align-center gap flex-item-grow txt-large input input--actor">
            <input class="input" autocomplete="username" placeholder="Email address" required type="email" name="user[email_address]" id="user_email_address">
            <img aria-hidden="true" class="colorize--black" src="${v.asset("email.svg")}" width="24" height="24">
          </label>
        </div>

        <div class="flex align-center gap">
          @template.helpers._translation_button(v = v, key = "password")
          <label class="flex align-center gap flex-item-grow txt-large input input--actor">
            <input class="input" autocomplete="new-password" placeholder="Password" required maxlength="72" size="72" type="password" name="user[password]" id="user_password">
            <img aria-hidden="true" class="colorize--black" src="${v.asset("password.svg")}" width="24" height="24">
          </label>
        </div>

        <button name="button" type="submit" class="btn btn--reversed center txt-large">
          <img aria-hidden="true" src="${v.asset("check.svg")}">
          <span class="for-screen-reader">Save</span>
        </button>
      </div>
    </section>
  </form>

  @template.accounts._help_contact(v = v, helpContact = helpContact)
`)
