@import campfire.web.Paths
@param campfire.view.View v
@param String customStyles
@template.layouts.application(v = v, title = "Custom styles", nav = @`
  <div class="flex-item-justify-start">
    @template.helpers._back_link(v = v, destination = Paths.editAccount())
  </div>
`, content = @`
  <section class="panel panel--wide txt-align-center flex flex-column position-relative" style="view-transition-name: custom-styles">
    <form class="flex flex-column gap" data-controller="form" data-action="keydown.ctrl+enter->form#submit keydown.meta+enter->form#submit" action="${v.url(Paths.accountCustomStyles())}" accept-charset="UTF-8" method="post">
      <input type="hidden" name="_method" value="patch">
      <div class="panel__button">
        @template.helpers._translation_button(v = v, key = "custom_styles")
      </div>

      <div class="pad-inline-double margin-inline">
        <h1 class="margin-none">Custom CSS</h1>
        <p class="flex flex-wrap align-center justify-center gap margin-none-block-start" style="--column-gap: 0.5ch; --row-gap: 0">
          <span>Add custom CSS styles.</span>
          <img class="flex-inline colorize--black" aria-hidden="true" src="${v.asset("alert.svg")}" width="16" height="16">
          <span>Use Caution: you could break things.</span>
        </p>
      </div>

      <label class="flex align-start gap flex-item-grow">
        <%-- The newline after the tag keeps a leading newline of the styles: browsers drop the first one. --%>
        <textarea class="input input--code txt--small" placeholder="Add CSS styles…" autocomplete="off" spellcheck="false" autocorrect="off" autocapitalize="off" rows="16" name="account[custom_styles]" id="account_custom_styles">
${customStyles}</textarea>
      </label>

      <button name="button" type="submit" class="btn btn--reversed center txt-large">
        <img aria-hidden="true" src="${v.asset("check.svg")}" width="20" height="20">
        <span class="for-screen-reader">Save changes</span>
      </button>
    </form>
  </section>
`)
