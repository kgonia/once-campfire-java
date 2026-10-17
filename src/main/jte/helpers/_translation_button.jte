@import campfire.view.View
@param View v
@param String key
<details class="position-relative" data-controller="popup" data-action="keydown.esc->popup#close toggle->popup#toggle click@document->popup#closeOnClickOutside" data-popup-orientation-top-class="popup-orientation-top">
  <summary class="btn" tabindex="-1">
    <img aria-hidden="true" class="color-icon" src="${v.asset("globe.svg")}" width="20" height="20">
    <span class="for-screen-reader">Translate</span>
  </summary>
  <div class="language-list-menu shadow" data-popup-target="menu">
    <dl class="language-list">
      @for(var translation : View.translations(key))
        <dt>${translation.flag()}</dt>
        <dd class="margin-none">${translation.text()}</dd>
      @endfor
    </dl>
  </div>
</details>
