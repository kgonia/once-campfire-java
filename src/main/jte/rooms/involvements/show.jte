@import campfire.view.View
@import campfire.web.Paths
@param View v
@param long roomId
@param String paramKey
@param boolean direct
@param String involvement
@template.layouts.application(v = v, content = @`
  <turbo-frame data-controller="turbo-frame" data-action="notifications:ready@window->turbo-frame#load" data-turbo-frame-url-param="${Paths.roomInvolvement(roomId)}" id="${View.domId(paramKey, roomId, "involvement")}">
    @template.rooms.involvements._button(v = v, roomId = roomId, paramKey = paramKey, direct = direct, involvement = involvement)
  </turbo-frame>
`)
