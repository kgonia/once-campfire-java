@import campfire.web.Paths
@param campfire.view.View v
@param campfire.users.User user
@param boolean lazy = false
<a title="${user.title()}" class="btn avatar" data-turbo-frame="_top" href="${Paths.user(user.id())}"><img aria-hidden="true" loading="${lazy ? "lazy" : null}" src="${v.avatarPath(user)}" width="48" height="48"></a>