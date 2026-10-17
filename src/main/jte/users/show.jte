@import campfire.view.View
@import campfire.web.Paths
@param View v
@param campfire.users.User user
@param String transferId
@template.layouts.application(v = v, title = user.name(), nav = @`
  <div class="flex-item-justify-start">
    @template.helpers._back_link(v = v, destination = v.backPath())
  </div>

  <div class="flex align-center gap flex-item-justify-end">
    @if(v.isCurrentUser(user.id()))
      <a class="btn" href="${Paths.USER_PROFILE}">
        <img aria-hidden="true" src="${v.asset("pencil.svg")}">
        <span class="for-screen-reader">Edit my profile</span>
      </a>
    @endif
  </div>
`, content = @`
  <section class="panel txt-align-center">
    <div class="flex flex-column gap ${user.isBanned() ? "banned" : ""}">
      <div class="avatar txt-xx-large center" style="background: white">
        <img alt="Profile avatar" class="avatar" src="${v.avatarPath(user)}">
      </div>

      @if(user.isBot())
        <div class="pad-double--inline push--inline push--block-start">
          @if(user.isActive())
            <form class="button_to" method="post" action="${View.roomsDirectsWithUser(user.id())}">
              <button class="btn btn--primary full-width txt--large" type="submit"><img src="${v.asset("messages.svg")}"></button>
            </form>
          @else
            <div>${user.name()} is no longer on this account</div>
          @endif
        </div>
      @elseif(!user.isDeactivated())
        <div class="flex flex-column gap" style="--row-gap: calc(var(--block-space) / 3)">
          <h1 class="txt-x-large txt-tight-lines margin-none">${user.name()}</h1>
          @if(v.canAdminister())
            !{String email = user.emailAddress() == null ? "" : user.emailAddress();}
            <div><a href="${View.mailtoUrl(email)}">${email}</a></div>
          @endif
          <div>${user.bio()}</div>
        </div>

        @if(user.isActive())
          <div class="pad-inline-double margin-inline margin-block-start">
            <form class="button_to" method="post" action="${View.roomsDirectsWithUser(user.id())}">
              <button class="btn btn--reversed full-width txt-large" type="submit">
                <img aria-hidden="true" aria-label="Ping ${user.name()}" src="${v.asset("messages.svg")}">
              </button>
            </form>
          </div>

          @if(v.canAdminister())
            <hr class="margin-block-start borderless">

            @template.users.profiles._transfer(v = v, user = user, transferId = transferId)
          @endif
        @endif

        @if(v.canAdminister() && !v.isCurrentUser(user.id()))
          <div class="margin-block-start">
            @template.users._ban_button(v = v, user = user)
          </div>
        @endif
      @else
        <div>
          <h1 class="txt-x-large margin-none">${user.name()}</h1>
          <div>${user.name()} is no longer on this account</div>
        </div>
      @endif
    </div>
  </section>
`)
