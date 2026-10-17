@import campfire.web.Paths
@param campfire.view.View v
@param campfire.users.UserPages.ProfileShow profile
!{var user = profile.user();}
@template.layouts.application(v = v, title = user.name(), nav = @`
  <div class="flex-item-justify-start">
    @template.helpers._back_link(v = v, destination = v.backPath())
  </div>

  <div class="flex-item-justify-end">
    <form data-controller="sessions" action="${Paths.SESSION}" accept-charset="UTF-8" method="post">
      <input type="hidden" name="_method" value="delete">
      <input type="hidden" name="push_subscription_endpoint" id="push_subscription_endpoint" data-sessions-target="pushSubscriptionEndpoint">

      <button class="btn" data-action="sessions#logout:prevent">
        <img aria-hidden="true" src="${v.asset("logout.svg")}">
        <span class="for-screen-reader">Log out</span>
      </button>
    </form>
  </div>
`, content = @`
  <section class="panel flex flex-column gap" style="view-transition-name: avatar-${user.id()}">
    @template.pwa._install_instructions(v = v)

    <div class="align-center center avatar__form gap" data-controller="upload-preview">
      <form class="txt-medium" data-controller="form" enctype="multipart/form-data" action="${Paths.USER_PROFILE}" accept-charset="UTF-8" method="post">
        <input type="hidden" name="_method" value="patch">
        <label class="btn input--file">
          <img aria-hidden="true" src="${v.asset("camera.svg")}" width="20" height="20">
          <input id="file" class="input" accept="image/*" data-upload-preview-target="input" data-action="upload-preview#previewImage change->form#submit" type="file" name="user[avatar]">
          <span class="for-screen-reader">Upload avatar</span>
        </label>
      </form>

      <form data-controller="form" enctype="multipart/form-data" action="${Paths.USER_PROFILE}" accept-charset="UTF-8" method="post">
        <input type="hidden" name="_method" value="patch">
        <label class="btn avatar input--file txt-xx-large">
          <img aria-hidden="true" data-upload-preview-target="image" src="${v.avatarPath(user)}" width="300" height="300">
          <input id="file" class="input" accept="image/*" data-upload-preview-target="input" data-action="upload-preview#previewImage change->form#submit" type="file" name="user[avatar]">
          <span class="for-screen-reader">Avatar</span>
        </label>
      </form>

      @if(profile.avatarAttached())
        <form class="button_to" method="post" action="${Paths.userAvatar(Long.toString(user.id()))}">
          <input type="hidden" name="_method" value="delete">
          <button class="btn btn--negative txt-small avatar__delete-btn" type="submit">
            <img aria-hidden="true" src="${v.asset("minus.svg")}" width="20" height="20">
            <span class="for-screen-reader">Delete avatar</span>
          </button>
        </form>
      @endif
    </div>

    <form data-controller="form" action="${Paths.USER_PROFILE}" accept-charset="UTF-8" method="post">
      <input type="hidden" name="_method" value="patch">
      <div class="flex flex-column gap">
        <div class="flex align-center gap">
          @template.helpers._translation_button(v = v, key = "user_name")

          <label class="flex align-center gap flex-item-grow input input--actor">
            <input class="input txt-large " autocomplete="name" placeholder="Enter your name" autofocus required data-1p-ignore="true" type="text" value="${user.name()}" name="user[name]" id="user_name">
            <img aria-hidden="true" class="colorize--black" src="${v.asset("person.svg")}" width="24" height="24">
          </label>
        </div>

        <div class="flex align-center gap">
          @template.helpers._translation_button(v = v, key = "email_address")

          <label class="flex align-center gap flex-item-grow input input--actor">
            <input class="input txt-large" value="${user.emailAddress()}" autocomplete="username" placeholder="Enter your email address" type="email" name="user[email_address]" id="user_email_address">
            <img aria-hidden="true" class="colorize--black" src="${v.asset("email.svg")}" width="24" height="24">
          </label>
        </div>

        <div class="flex align-center gap">
          @template.helpers._translation_button(v = v, key = "update_password")

          <label class="flex align-center gap flex-item-grow input input--actor">
            <input class="input txt-large" autocomplete="new-password" placeholder="Change password" maxlength="72" size="72" type="password" name="user[password]" id="user_password">
            <img aria-hidden="true" class="colorize--black" src="${v.asset("password.svg")}" width="24" height="24">
          </label>
        </div>

        <div class="flex align-start gap">
          @template.helpers._translation_button(v = v, key = "bio")

          <label class="flex align--center gap flex-item--grow input input--actor">
            <textarea class="input txt-large" placeholder="A few words about yourself…" maxlength="200" rows="3" name="user[bio]" id="user_bio">
${user.bio()}</textarea>
            <img aria-hidden="true" class="colorize--black" src="${v.asset("bio.svg")}" width="24" height="24">
          </label>
        </div>

        <button class="btn btn--reversed center txt-large" type="submit">
          <img aria-hidden="true" src="${v.asset("check.svg")}" width="20" height="20">
          <span class="for-screen-reader">Save changes</span>
        </button>
      </div>
    </form>

    <div class="margin-block pad-inline pad-block fill-shade border-radius">
      <menu class="flex flex-column gap margin-none pad">
        @for(var membership : profile.sharedMemberships())
          @template.users.profiles._membership(v = v, membership = membership)
        @endfor
        @if(!profile.directMemberships().isEmpty() && !profile.sharedMemberships().isEmpty())
          <hr class="separator full-width" style="--border-style: solid">
        @endif
        @for(var membership : profile.directMemberships())
          @template.users.profiles._membership(v = v, membership = membership)
        @endfor
      </menu>
    </div>

    @template.users.profiles._transfer(v = v, user = user, transferId = profile.transferId())
  </section>
`)
