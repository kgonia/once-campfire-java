@import campfire.view.*
@param View v
@param campfire.sidebar.SidebarPage.SharedRoom room
${v.linkToRoom(room.id(), Attrs.of().set("id", View.domId(room.paramKey(), room.id(), "list")).data("sorted_list_name", room.name()).set("style", "--column-gap: 0.5em").set("class", room.classNames()), @`
  <span class="overflow-ellipsis">${room.name()}</span>
`)}