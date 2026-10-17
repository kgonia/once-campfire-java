@import campfire.rooms.RoomForms.DirectEdit
@import campfire.users.UserView
@import campfire.view.*
@import campfire.web.Paths
@param View v
@param DirectEdit edit
@template.layouts.application(v = v, title = "Edit settings for " + edit.displayName(),
  nav = @`
  <div class="flex-item-justify-start">
    ${v.linkBackToLastRoomVisited()}
  </div>`,
  content = @`
<div class="panel txt-align-center">
  <section class="directs--edit margin-block-end">@for(UserView user : edit.users())
      <div class="member flex flex-column gap fill-shade pad border-radius">
        <figure class="avatar center" style="--avatar-border-radius: 10ch; --avatar-size: 10ch;" >
          <a title="${user.title()}" class="btn avatar" data-turbo-frame="_top" href="${user.path()}"><img aria-hidden="true" loading="lazy" src="${user.avatarUrl()}" width="48" height="48" /></a>
        </figure>

        <strong>${user.name()}</strong>
      </div>@endfor
  </section>

  <form class="button_to" method="post" action="${v.url(Paths.roomsDirect(edit.roomId()))}"><input type="hidden" name="_method" value="delete" /><button class="btn btn--negative center" aria-label="Delete Ping" data-turbo-confirm="Are you sure you want to delete this ping and all messages in it? This can’t be undone." type="submit">
    <img aria-hidden="true" src="${v.asset("trash.svg")}" />
    Ping
</button></form></div>`)