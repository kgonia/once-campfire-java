@import campfire.view.*
@import campfire.web.Paths
@param View v
@param campfire.users.User user
@param String transferId
@template.layouts.application(v = v, title = user.name(),
  nav = @`  <div class="flex-item-justify-start">
    ${v.linkBack()}
  </div>

  <div class="flex align-center gap flex-item-justify-end">
@if(v.isCurrentUser(user.id()))      ${View.linkTo(Paths.USER_PROFILE, Attrs.of("class", "btn"), @`
        ${v.imageTag("pencil.svg", Attrs.of().aria("hidden", "true"))}
        <span class="for-screen-reader">Edit my profile</span>
`)}@endif  </div>
`,
  content = @`<section class="panel txt-align-center">
  <div class="flex flex-column gap @if(user.isBanned())banned@endif">
    <div class="avatar txt-xx-large center" style="background: white">
      ${v.imageTag(v.avatarPath(user), Attrs.of("alt", "Profile avatar", "class", "avatar"))}
    </div>

@if(user.isBot())      <div class="pad-double--inline push--inline push--block-start">
@if(user.isActive())          ${v.buttonToDirectRoomWith(user.id())}
@else          <div>${user.name()} is no longer on this account</div>
@endif      </div>
@else@if(!user.isDeactivated())        <div class="flex flex-column gap" style="--row-gap: calc(var(--block-space) / 3)">
          <h1 class="txt-x-large txt-tight-lines margin-none">${user.name()}</h1>
@if(v.canAdminister())            <div>${View.mailTo(user.emailAddress() == null ? "" : user.emailAddress())}</div>
@endif          <div>${user.bio() == null ? "" : user.bio()}</div>
        </div>

@if(user.isActive())          <div class="pad-inline-double margin-inline margin-block-start">
            ${View.buttonTo(View.roomsDirectsWithUser(user.id()), Attrs.of("class", "btn btn--reversed full-width txt-large"), @`
              ${v.imageTag("messages.svg", Attrs.of().aria("hidden", "true", "label", "Ping " + user.name()))}
`)}          </div>

@if(v.canAdminister())            <hr class="margin-block-start borderless">

            @template.users.profiles._transfer(v = v, user = user, transferId = transferId)
@endif@endif
@if(v.canAdminister() && !v.isCurrentUser(user.id()))          <div class="margin-block-start">
            @template.users._ban_button(v = v, user = user)
          </div>
@endif@else        <div>
          <h1 class="txt-x-large margin-none">${user.name()}</h1>
          <div>${user.name()} is no longer on this account</div>
        </div>
@endif@endif  </div>
</section>
`)