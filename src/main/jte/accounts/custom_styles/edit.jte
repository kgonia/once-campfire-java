@import campfire.view.*
@import campfire.web.Paths
@param View v
@param String customStyles
!{var form = View.formWith(v.url(Paths.accountCustomStyles())).model("account").method("patch").cssClass("flex flex-column gap").data("controller", "form").data("action", "keydown.ctrl+enter->form#submit keydown.meta+enter->form#submit");}@template.layouts.application(v = v, title = "Custom styles",
  nav = @`  <div class="flex-item-justify-start">
    ${v.linkBackTo(Paths.editAccount())}
  </div>
`,
  content = @`<section class="panel panel--wide txt-align-center flex flex-column position-relative" style="view-transition-name: custom-styles">
  ${form.wrap(@`
    <div class="panel__button">
      ${v.translationButton("custom_styles")}
    </div>

    <div class="pad-inline-double margin-inline">
      <h1 class="margin-none">Custom CSS</h1>
      <p class="flex flex-wrap align-center justify-center gap margin-none-block-start" style="--column-gap: 0.5ch; --row-gap: 0">
        <span>Add custom CSS styles.</span>
        ${v.imageTag("alert.svg", Attrs.of().set("class", "flex-inline colorize--black").set("size", 16).aria("hidden", "true"))}
        <span>Use Caution: you could break things.</span>
      </p>
    </div>

    <label class="flex align-start gap flex-item-grow">
      ${form.textArea("custom_styles", customStyles, Attrs.of().set("class", "input input--code txt--small").set("placeholder", "Add CSS styles…").set("autocomplete", "off").set("spellcheck", "false").set("autocorrect", "off").set("autocapitalize", "off").set("rows", 16).set("required", false))}
    </label>

    ${form.button(Attrs.of().set("class", "btn btn--reversed center txt-large").set("type", "submit"), @`
      ${v.imageTag("check.svg", Attrs.of().aria("hidden", "true").set("size", 20))}
      <span class="for-screen-reader">Save changes</span>
`)}`)}</section>
`)