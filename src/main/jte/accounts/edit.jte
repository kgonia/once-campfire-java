@import campfire.accounts.AccountPage
@import campfire.users.User
@import campfire.view.*
@import campfire.web.Paths
@param View v
@param AccountPage page
!{boolean restricted = page.account().restrictRoomCreationToAdministrators();}
@template.layouts.application(v = v, title = "Account settings", nav = @`
  <div class="flex-item-justify-start">
    @template.helpers._back_link(v = v, destination = v.lastRoomVisitedPath())
  </div>

  @if(v.canAdminister())
    <div class="flex align-center gap flex-item-justify-end">
      <a class="btn" style="view-transition-name: chat-bots" href="${Paths.accountBots()}">
        <img aria-hidden="true" src="${v.asset("bot.svg")}" width="20" height="20">
        <span class="for-screen-reader">Set up chat bots</span>
      </a>
      <a class="btn" style="view-transition-name: custom-styles" href="${Paths.editAccountCustomStyles()}">
        <img aria-hidden="true" src="${v.asset("art.svg")}" width="20" height="20">
        <span class="for-screen-reader">Custom styles</span>
      </a>
    </div>
  @endif
`, content = @`
  <section class="panel txt-align-center flex flex-column gap" style="view-transition-name: account-settings">
    @if(v.canAdminister())
      <div class="align-center center avatar__form gap" data-controller="upload-preview">
        <form class="txt--medium" data-controller="form" enctype="multipart/form-data" action="${page.action()}" accept-charset="UTF-8" method="post">
          <input type="hidden" name="_method" value="patch">
          <label class="btn input--file">
            <img aria-hidden="true" src="${v.asset("camera.svg")}" width="20" height="20">
            <input class="input" accept="image/*" data-action="upload-preview#previewImage change->form#submit" type="file" name="account[logo]" id="account_logo">
            <span class="for-screen-reader">Upload logo</span>
          </label>
        </form>

        <form data-controller="form" enctype="multipart/form-data" action="${page.action()}" accept-charset="UTF-8" method="post">
          <input type="hidden" name="_method" value="patch">
          <label class="btn avatar input--file account-logo txt-xx-large">
            <img role="presentation" data-upload-preview-target="image" src="${v.freshAccountLogoPath()}" width="48" height="48">
            <input class="input" accept="image/*" data-action="upload-preview#previewImage change->form#submit" type="file" name="account[logo]" id="account_logo">
            <span class="for-screen-reader">Upload logo</span>
          </label>
        </form>

        @if(v.accountHasLogo)
          <form class="button_to" method="post" action="${v.freshAccountLogoPath()}">
            <input type="hidden" name="_method" value="delete">
            <button class="btn btn--negative txt-small avatar__delete-btn" type="submit">
              <img aria-hidden="true" src="${v.asset("minus.svg")}" width="20" height="20">
              <span class="for-screen-reader">Delete logo</span>
            </button>
          </form>
        @endif
      </div>

      <form class="flex flex-column gap" data-controller="form" action="${page.action()}" accept-charset="UTF-8" method="post">
        <input type="hidden" name="_method" value="patch">
        <div class="flex align-center gap">
          @template.helpers._translation_button(v = v, key = "account_name")

          <label class="flex align-center gap flex-item-grow">
            <input class="input txt-large" autocomplete="off" placeholder="Name this account" autofocus data-action="keydown.enter->form#submit" type="text" value="${page.account().name()}" name="account[name]" id="account_name">
          </label>

          <button name="button" type="submit" class="btn btn--reversed center">
            <img aria-hidden="true" src="${v.asset("check.svg")}" width="20" height="20">
            <span class="for-screen-reader">Save changes</span>
          </button>
        </div>
      </form>

      <div class="margin-block-start pad-block pad-inline-double fill-shade border-radius">
        <form class="flex align-center gap center" data-controller="form" action="${page.action()}" accept-charset="UTF-8" method="post">
          <input type="hidden" name="_method" value="put">
          <div class="flex-item-grow flex align-center gap txt-align-start">
            <img class="colorize--black" aria-hidden="true" src="${v.asset("crown.svg")}" width="18" height="18"> Must be admin to create new rooms
          </div>
          <input value="${String.valueOf(!restricted)}" type="hidden" name="account[settings][restrict_room_creation_to_administrators]" id="account_settings_restrict_room_creation_to_administrators">

          <label class="switch">
            <input type="checkbox" class="switch__input" checked="${restricted}" data-action="change->form#submit">
            <span class="switch__btn round"></span>
            <span class="for-screen-reader">Must be admin to create new rooms</span>
          </label>
        </form>
      </div>
    @else
      @template.helpers._account_logo(v = v, style = "txt-xx-large center")
      <h1 class="flex-item-grow txt-x-large">${page.account().name()}</h1>
    @endif

    <div class="margin-block pad-inline pad-block-start fill-shade border-radius">
      @template.accounts._invite(v = v, joinCode = page.account().joinCode())

      <hr class="margin-block separator full-width" style="--border-style: solid">

      <menu class="flex flex-column gap margin-none pad">
        <turbo-frame id="account_users">
          @for(User user : page.administrators())
            @template.accounts.users._user(v = v, user = user)
          @endfor
          @if(!page.administrators().isEmpty() && !page.members().isEmpty())
            <hr class="separator full-width" style="--border-style: solid">
          @endif
          @for(User user : page.members())
            @template.accounts.users._user(v = v, user = user)
          @endfor
          @if(page.nextPage() != null)
            @template.accounts.users._next_page_container(page = page.nextPage())
          @endif
        </turbo-frame>
      </menu>
    </div>
  </section>
`, footer = @`
  <div class="txt-align-center center margin-block-double txt-subtle">Campfire&trade; version <span class="version-badge">${v.appVersion()}</span></div>
`)
