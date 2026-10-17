@import campfire.messages.*
@param campfire.view.View v
@param BoostView boost
<div id="${boost.domId()}"
      class="boost boost-item flex-inline postion--relative max-width align-center fill-white gap"
      data-controller="boost-delete" data-boost-delete-perform-class="boost--deleting" data-boost-delete-reveal-class="expanded" data-boost-delete-booster-id-value="${boost.booster().id()}">
    <figure class="avatar boost__avatar flex-item-no-shrink">
      <a title="${boost.booster().title()}" class="btn avatar" data-turbo-frame="_top" href="${boost.booster().path()}"><img aria-label="${boost.booster().name()} boosted ${boost.content()}" src="${boost.booster().avatarUrl()}" width="48" height="48" /></a>
    </figure>

    <span role="button" class="${boost.allEmoji() ? "txt-small txt-medium" : "txt-small"}" data-action="click-&gt;boost-delete#reveal keydown.enter-&gt;boost-delete#reveal:prevent" data-boost-delete-target="content">${boost.content()}</span>

    <form class="button_to" method="post" action="${boost.path()}"><input type="hidden" name="_method" value="delete" /><button data-action="boost-delete#perform" data-boost-delete-target="button" class="btn btn--negative flex-item-justify-end boost__delete" type="submit">
      <img aria-hidden="true" src="${v.asset("minus.svg")}" width="20" height="20" />
      <span class="for-screen-reader">Delete this boost</span>
</button></form>  </div>
  <span id="delete_boost_accessible_label" class="for-screen-reader">Press enter to delete this boost</span>