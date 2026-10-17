@import campfire.view.*
@import campfire.web.Paths
@param View v
@param campfire.users.UserPages.ProfileShow profile
!{var user = profile.user();}!{var logout = View.formWith(Paths.SESSION).method("delete").data("controller", "sessions");}!{var avatarUpload = View.formWith(Paths.USER_PROFILE).model("user").method("patch").data("controller", "form").cssClass("txt-medium");}!{var avatarPreview = View.formWith(Paths.USER_PROFILE).model("user").method("patch").data("controller", "form");}!{var details = View.formWith(Paths.USER_PROFILE).model("user").method("patch").data("controller", "form");}@template.layouts.application(v = v, title = user.name(),
  nav = @`  <div class="flex-item-justify-start">
    ${v.linkBack()}
  </div>

  <div class="flex-item-justify-end">
    ${logout.wrap(Safe.from(@`
      ${View.hiddenFieldTag("push_subscription_endpoint", null, Attrs.of().data("sessions_target", "pushSubscriptionEndpoint"))}

      <button class="btn" data-action="sessions#logout:prevent">
        ${v.imageTag("logout.svg", Attrs.of().aria("hidden", "true"))}
        <span class="for-screen-reader">Log out</span>
      </button>
`))}  </div>
`,
  content = @`<section class="panel flex flex-column gap" style="view-transition-name: avatar-${user.id()}">
  @template.pwa._install_instructions(v = v)

  <div class="align-center center avatar__form gap" data-controller="upload-preview">
    ${avatarUpload.wrap(Safe.from(@`
      <label class="btn input--file">
        ${v.imageTag("camera.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
        ${avatarUpload.fileField("avatar", Attrs.of("id", "file", "class", "input", "accept", "image/*").data("upload_preview_target", "input", "action", "upload-preview#previewImage change->form#submit"))}
        <span class="for-screen-reader">Upload avatar</span>
      </label>
`))}
    ${avatarPreview.wrap(Safe.from(@`
      <label class="btn avatar input--file txt-xx-large">
        ${v.imageTag(v.avatarPath(user), Attrs.of().aria("hidden", "true").set("size", 300).data("upload_preview_target", "image"))}
        ${avatarPreview.fileField("avatar", Attrs.of("id", "file", "class", "input", "accept", "image/*").data("upload_preview_target", "input", "action", "upload-preview#previewImage change->form#submit"))}
        <span class="for-screen-reader">Avatar</span>
      </label>
`))}
@if(profile.avatarAttached())      ${View.buttonTo(Paths.userAvatar(Long.toString(user.id())), Attrs.of("method", "delete", "class", "btn btn--negative txt-small avatar__delete-btn"), @`
        ${v.imageTag("minus.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
        <span class="for-screen-reader">Delete avatar</span>
`)}@endif  </div>

  ${details.wrap(Safe.from(@`
    <div class="flex flex-column gap">
      <div class="flex align-center gap">
        ${v.translationButton("user_name")}

        <label class="flex align-center gap flex-item-grow input input--actor">
          ${details.textField("name", user.name(), Attrs.of("class", "input txt-large ", "autocomplete", "name", "placeholder", "Enter your name", "autofocus", true, "required", true).data("1p-ignore", true))}
          ${v.imageTag("person.svg", Attrs.of().aria("hidden", "true").set("size", 24).set("class", "colorize--black"))}
        </label>
      </div>

      <div class="flex align-center gap">
        ${v.translationButton("email_address")}

        <label class="flex align-center gap flex-item-grow input input--actor">
          ${details.emailField("email_address", null, Attrs.of("class", "input txt-large", "value", user.emailAddress(), "autocomplete", "username", "placeholder", "Enter your email address", "required", false))}
          ${v.imageTag("email.svg", Attrs.of().aria("hidden", "true").set("size", 24).set("class", "colorize--black"))}
        </label>
      </div>

      <div class="flex align-center gap">
        ${v.translationButton("update_password")}

        <label class="flex align-center gap flex-item-grow input input--actor">
          ${details.passwordField("password", Attrs.of("class", "input txt-large", "autocomplete", "new-password", "placeholder", "Change password", "required", false, "maxlength", 72))}
          ${v.imageTag("password.svg", Attrs.of().aria("hidden", "true").set("size", 24).set("class", "colorize--black"))}
        </label>
      </div>

      <div class="flex align-start gap">
        ${v.translationButton("bio")}

        <label class="flex align--center gap flex-item--grow input input--actor">
          ${details.textArea("bio", user.bio(), Attrs.of("class", "input txt-large", "placeholder", "A few words about yourself…", "maxlength", 200, "rows", 3, "required", false))}
          ${v.imageTag("bio.svg", Attrs.of().aria("hidden", "true").set("size", 24).set("class", "colorize--black"))}
        </label>
      </div>

      ${v.profileFormSubmitButton()}
    </div>
`))}
  <div class="margin-block pad-inline pad-block fill-shade border-radius">
    <menu class="flex flex-column gap margin-none pad">
      @for(var membership : profile.sharedMemberships())@template.users.profiles._membership(v = v, membership = membership)@endfor
@if(!profile.directMemberships().isEmpty() && !profile.sharedMemberships().isEmpty())        <hr class="separator full-width" style="--border-style: solid">
@endif
      @for(var membership : profile.directMemberships())@template.users.profiles._membership(v = v, membership = membership)@endfor    </menu>
  </div>

  @template.users.profiles._transfer(v = v, user = user, transferId = profile.transferId())
</section>
`)