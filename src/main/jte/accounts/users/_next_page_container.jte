@import campfire.view.*
@param String page
${View.turboFrameTag("next_page_container", "/account/users.turbo_stream?page=" + View.cgiEscape(page), null, Attrs.of().set("loading", "lazy").set("class", "flex center"), @`
  <div class="spinner center"></div>
`)}