@import campfire.web.Paths
@param campfire.view.View v
@template.layouts.application(v = v, title = "Set up Campfire", bodyClass = "signup", content = @`
  <form class="center max-width" enctype="multipart/form-data" action="${Paths.FIRST_RUN}" accept-charset="UTF-8" method="post">
    <section class="nametag u-relative">
      <div class="flex justify-center align-center pad-block">
        <img class="nametag__lanyard" aria-hidden="true" src="${v.asset("lanyard.svg")}">
      </div>

      <div class="nametag__inner flex flex-column gap">
        <fieldset class="flex flex-column center-block">
          <legend class="txt-large txt-align-center"><strong>Set up Campfire</strong></legend>

          <label class="align-center center avatar__form gap" data-controller="upload-preview">
            <div class="btn input--file">
              <img aria-hidden="true" src="${v.asset("camera.svg")}">
              <input class="input" accept="image/*" data-upload-preview-target="input" data-action="upload-preview#previewImage" type="file" name="user[avatar]" id="user_avatar">
              <span class="for-screen-reader">Add your avatar</span>
            </div>

            <div class="btn avatar input--file txt-xx-large">
              <img aria-hidden="true" data-upload-preview-target="image" alt="Add your avatar" src="${v.asset("default-avatar.svg")}">
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
          <img aria-hidden="true" src="${v.asset("arrow-right.svg")}">
          <span class="for-screen-reader">Save</span>
        </button>
      </div>
    </section>
  </form>
`)
