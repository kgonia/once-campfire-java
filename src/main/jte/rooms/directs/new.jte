@import campfire.web.Paths
@param campfire.view.View v
@template.layouts.application(v = v, content = @`
  <turbo-frame id="direct_rooms_control" target="_top">
    <div class="directs directs--new flex flex-column gap">
      <form class="flex gap flex-item-grow" data-controller="form" data-action="keydown.esc->form#cancel" action="${Paths.roomsDirects()}" accept-charset="UTF-8" method="post">
        <a class="btn flex-item-no-shrink" data-turbo-frame="user_sidebar" data-form-target="cancel" href="${Paths.userSidebar()}">
          <img aria-hidden="true" src="${v.asset("arrow-left.svg")}">
          <span class="for-screen-reader">Cancel changes</span>
        </a>

        <section class="autocomplete__container unpad input input--actor">
          <div class="autocomplete__input input flex flex-wrap position-relative flex-item-grow"
              data-controller="autocomplete" data-autocomplete-url-value="${Paths.autocompletableUsers()}">
            <select name="user_ids[]" data-autocomplete-target="select" data-template-id="autocompletable-user" multiple hidden required></select>

            @template.users.autocompletables._template(v = v)

            <input autocomplete="off" autocorrect="off" data-1p-ignore="true" class="autocomplete__input input flex flex-wrap position-relative" data-autocomplete-target="input" data-action="input->autocomplete#search keydown->autocomplete#didPressKey" type="text" name="rooms_direct[user_ids_input]" id="rooms_direct_user_ids_input">
          </div>
        </section>

        <button name="button" type="submit" class="btn btn--reversed flex-item-no-shrink">
          <img aria-hidden="true" src="${v.asset("check.svg")}">
          <span class="for-screen-reader">Start Ping</span>
        </button>
      </form>
      <span class="txt-small translucent pad-inline-half center">Type names to ping someone…</span>
    </div>
  </turbo-frame>
`)
