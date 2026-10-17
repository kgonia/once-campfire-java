@import campfire.view.View
@import campfire.web.Paths
@param View v
@param long roomId
@param String paramKey
@param boolean direct
@param String involvement
!{String labelId = View.domId(paramKey, roomId, "involvement_label");}
<form class="button_to" method="post" action="${Paths.roomInvolvement(roomId)}?involvement=${View.nextInvolvement(direct, involvement)}">
  <input type="hidden" name="_method" value="put">
  <button role="checkbox" aria-checked="true" aria-labelledby="${labelId}" tabindex="0" class="btn ${involvement}" type="submit">
    <img aria-hidden="true" src="${v.asset("notification-bell-" + involvement + ".svg")}" width="20" height="20">
    <span class="for-screen-reader" id="${labelId}">${View.humanizeInvolvement(involvement)}</span>
  </button>
</form>
