@import campfire.messages.*
@param campfire.view.View v
@param MessageView message
<turbo-frame id="${message.domId("boosting")}">
  <div class="boosts flex flex-wrap align-center gap full-width" style="--column-gap: 0.4ch; --row-gap: 0"
      data-controller="turbo-streaming" data-action="turbo:submit-start->turbo-streaming#unsubscribe">
    <div class="flex-inline flex-wrap gap" id="${message.domId("boosts")}" data-turbo-streaming-target="container">@for(BoostView boost : message.boosts())
      ${MessageFragments.boost(v, boost)}@endfor
    </div>

    <turbo-frame id="${message.domId("new_boost")}">
      <div class="flex-inline message__boost-inline" data-controller="soft-keyboard">
        <a class="boost__action txt-small btn" action="soft-keyboard#open" href="${message.newBoostPath()}">
          <img aria-hidden="true" src="${v.asset("boost.svg")}" width="20" height="20" />
          <span class="for-screen-reader">Add a boost</span>
</a>      </div>
</turbo-frame>  </div>
</turbo-frame>