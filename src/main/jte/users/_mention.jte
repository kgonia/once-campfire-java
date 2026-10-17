@import campfire.view.*
@param View v
@param campfire.users.UserPages.MentionUser user
<span class="mention" sgid="${user.attachableSgid()}">${v.avatarTag(user.user(), Attrs.of())} ${user.user().name()}</span>
