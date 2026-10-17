@import campfire.messages.*
@import campfire.users.UserView
@param campfire.view.View v
@param MessageView message
@param UserView user
@template.layouts.application(v = v, content = @`<turbo-frame id="${message.domId("new_boost")}">
  <div class="boost flex-inline postion--relative max-width fill-white" style="--column-gap: var(--inline-space-half)">
    <form class="boost__form flex align-center gap expanded" data-controller="form scroll-into-view" data-turbo-frame="${message.domId("boosting")}" data-action="keydown.esc-&gt;form#cancel" action="${message.boostsPath()}" accept-charset="UTF-8" method="post">
      <label class="boost__form-label flex gap" style="--column-gap: 0.7ch;" role="button" tabindex="0" aria-label="Add a boost">
        <figure class="avatar boost__avatar flex-item-no-shrink">
          <a title="${user.title()}" class="btn avatar" data-turbo-frame="_top" href="${user.path()}"><img aria-hidden="true" src="${user.avatarUrl()}" width="48" height="48" /></a>
          <span class="for-screen-reader">${user.name()}</span>
        </figure>

        <input autofocus="autofocus" autocomplete="off" autocorrect="off" maxlength="16" required="required" pattern="\S+.*" data-boost-form-target="input" class="input input--boost txt-small" size="16" type="text" name="boost[content]" />
      </label>

      <button name="button" type="submit" class="btn btn--reversed">
        <img aria-hidden="true" src="${v.asset("check.svg")}" />
        <span class="for-screen-reader">Submit</span>
</button>
      <a data-turbo-frame="${message.domId("boosts")}" data-form-target="cancel" class="btn btn--negative" href="${message.boostsPath()}">
        <img aria-hidden="true" src="${v.asset("minus.svg")}" />
        <span class="for-screen-reader">Cancel</span>
</a></form>  </div>
</turbo-frame>`)