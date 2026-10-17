@import campfire.web.Paths
@import java.util.List
@param campfire.view.View v
@param List<String> recentSearches
@for(String search : recentSearches)
  <a class="align-center gap room btn txt-nowrap" href="${Paths.searches(search)}">
    <span class="overflow-ellipsis">“${search}”</span>
  </a>
@endfor
@if(!recentSearches.isEmpty())
  <form class="button_to" method="post" action="${v.url(Paths.clearSearches())}">
    <input type="hidden" name="_method" value="delete">
    <button class="btn searches__btn" data-turbo-confirm="Are you sure you want to clear your recent searches?" type="submit">
      <img aria-hidden="true" src="${v.asset("broom.svg")}">
      <span class="for-screen-reader">Clear recent searches</span>
    </button>
  </form>
@endif
