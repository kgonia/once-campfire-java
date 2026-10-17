@import campfire.view.*
@import campfire.web.Paths
@param View v
@param campfire.search.SearchPage index
@template.layouts.application(v = v, title = "Search", bodyClass = "sidebar searches", nav = @`
  @if(index.query() != null)
    <div class="searches__query flex align-center gap pad-block-start-half">
      <div class="btn btn--reversed btn--faux align-center gap txt-nowrap">
        <span class="overflow-ellipsis">“${index.query()}”</span>
        <span class="flex-item-no-shrink">${index.messages().size()}</span>
      </div>
    </div>
  @endif

  <div class="searches__recents align-center gap pad-block-half overflow-y overflow-hide-scrollbar">
    @template.searches._recents(v = v, recentSearches = index.recentSearches())
  </div>
`, sidebar = @`
  <div class="rooms position-relative flex flex-column gap overflow-y overflow-hide-scrollbar">
    @template.searches._recents(v = v, recentSearches = index.recentSearches())
  </div>
`, content = @`
  <div id="message-area" class="message-area">
    <div class="message-area--empty min-width center">
      <figure class="center pad">
        <img aria-hidden="true" class="colorize--black translucent" src="${v.asset("search.svg")}">
      </figure>
    </div>

    <div id="search-results" class="messages searches__results" data-controller="search-results" data-search-results-target="messages" data-search-results-me-class="message--me" data-search-results-threaded-class="message--threaded" data-search-results-mentioned-class="message--mentioned" data-search-results-formatted-class="message--formatted">
      @for(Safe message : index.messages())
        ${message.handed()}
      @endfor
    </div>
  </div>
`, footer = @`
  <div class="composer flex align-end gap">
    <a class="btn flex-item-no-shrink margin-block-end" style="view-transition-name: input-switcher; --btn-border-radius: 0.5em" href="${Paths.room(index.returnToRoomId())}">
      <img aria-hidden="true" src="${v.asset("arrow-left.svg")}">
      <span class="for-screen-reader">Exit search </span>
    </a>

    <form class="margin-block flex-item-grow contain flex align-center gap" data-controller="form" data-action="keydown.esc->form#cancel" action="${Paths.searches()}" accept-charset="UTF-8" method="post">
      <div class="composer__input flex align-center flex-item-grow gap full-width input input--actor min-width">
        <img aria-hidden="true" class="composer__input-hint colorize--black" style="view-transition-name: input-btn;" src="${v.asset("search.svg")}" width="20" height="20">

        <input value="${index.q()}" class="searches__input input flex-item-grow" role="searchbox" aria-label="search" autofocus required type="text" name="q" id="q">

        <a data-form-target="cancel" role="button" class="searches__reset" href="${Paths.searches()}">
          <img aria-hidden="true" class="colorize--black" src="${v.asset("remove.svg")}" width="14" height="14">
          <span class="for-screen-reader">Clear search field</span>
        </a>

        <button name="button" type="submit" class="btn btn--reversed flex-item-no-shrink txt-small" style="--btn-border-radius: 0.5em">
          <img aria-hidden="true" src="${v.asset("arrow-up.svg")}">
          <span class="for-screen-reader">Search</span>
        </button>
      </div>
    </form>
  </div>
`)
