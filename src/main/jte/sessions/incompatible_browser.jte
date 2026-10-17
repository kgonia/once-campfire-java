@import campfire.view.View
@param View v
@template.layouts.application(v = v, title = v.platform.appleMessages() ? "Campfire" : "Unsupported browser", content = @`
  <div class="panel center">
    <header>
      <h1 class="txt-x-large txt-tight-lines txt-align-center margin-none-block-start margin-block-end">
        Upgrade to a supported web browser
      </h1>
      <div class="flex align-start gap">
        @template.helpers._translation_button(v = v, key = "incompatible_browser_messsage")
        <p class="margin-none-block-start">Campfire requires a modern web browser. Please use one of the browsers listed below and make sure auto-updates are enabled.</p>
      </div>
    </header>

    <div class="browser-list flex align-center flex-wrap gap justify-center margin-block">
      @for(String[] browser : new String[][] {{"safari", "17.2"}, {"chrome", "120"}, {"firefox", "121"}, {"opera", "104"}})
        <div class="browser flex flex-column">
          <img aria-hidden="true" class="center" src="${v.asset("browsers/" + browser[0] + ".svg")}">
          <div class="flex flex-column align-center margin-block-start-half">
            <strong>${View.capitalize(browser[0])}</strong>
            <span> ${browser[1]}+</span>
          </div>
        </div>
      @endfor
    </div>
  </div>
`)
