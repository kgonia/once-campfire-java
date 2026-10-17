@import campfire.users.User
@import campfire.view.*
@import campfire.web.Paths
@param View v
@param User user
!{String roleId = View.domId("user", user.id(), "role");}<li class="flex align-center gap margin-none ${user.isBanned() ? "banned" : ""}">
  <figure class="avatar flex-item-no-shrink" style="--avatar-size: 3.75ch;">
    ${v.avatarTag(user, Attrs.of().set("loading", "lazy"))}
  </figure>

  <div class="min-width">
    <div class="overflow-ellipsis fill-shade"><strong>${user.name()}</strong></div>
  </div>

  <hr class="separator" aria-hidden="true">

@if(v.canAdminister() && user.isActive())@if(!user.isBot())!{var form = View.formWith(Paths.accountUser(user.id())).model("user").data("controller", "form").method("patch");}      ${form.wrap(@`
        <label class="btn txt-small flex-item-no-shrink" for="${roleId}">
          <span class="for-screen-reader">Role: ${user.isAdministrator() ? "Administrator" : "Member"}</span>
          ${v.imageTag("crown.svg", Attrs.of().set("size", 20).aria("hidden", "true"))}
          ${form.checkBox("role", Attrs.of().data("action", "form#submit").set("hidden", true).set("id", roleId).set("disabled", v.isCurrentUser(user.id())), "administrator", "member", user.roleName())}
        </label>
`)}@endif
@if(!v.isCurrentUser(user.id()))      ${View.buttonTo(Paths.accountUser(user.id()), "delete", Attrs.of().set("class", "btn txt-small flex-item-no-shrink btn--negative").data("turbo_confirm", "Are you sure you want to permanently remove this person from the account? This can’t be undone."), @`
        ${v.imageTag("minus.svg", Attrs.of().set("size", 20).aria("hidden", "true"))}
        <span class="for-screen-reader">Delete ${user.name()}</span>
`)}@endif@endif@if(v.isCurrentUser(user.id()))    ${View.linkTo(Paths.userProfile(), Attrs.of().set("class", "btn txt-small flex-item-no-shrink").set("target", "_top"), @`
      ${v.imageTag("pencil.svg", Attrs.of().set("size", 20).aria("hidden", "true"))}
      <span class="for-screen-reader">My settings</span>
`)}@endif</li>
