@import campfire.view.View
@param String page
<turbo-frame loading="lazy" class="flex center" id="next_page_container" src="/account/users.turbo_stream?page=${View.cgiEscape(page)}">
  <div class="spinner center"></div>
</turbo-frame>
