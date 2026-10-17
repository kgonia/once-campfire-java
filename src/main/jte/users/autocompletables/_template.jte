@param campfire.view.View v
<template id="autocompletable-user">
  <div class="autocomplete__pill max-width" data-value="" tabindex="0">
    <img class="avatar flex-item-no-shrink" data-content="avatar" src="">
    <span class="autocomplete-field__selected-value-text overflow-ellipsis flex-item-grow" data-content="label"></span>

    <button type="button" data-action="autocomplete#remove:prevent" data-value="" tabindex="-1" class="btn btn--plain txt-small translucent flex-item-no-shrink">
      <img aria-hidden="true" class="colorize--black" src="${v.asset("remove-circle.svg")}">
      <span class="for-screen-reader">Remove <span data-content="screenReaderLabel"></span></span>
    </button>
  </div>
</template>
