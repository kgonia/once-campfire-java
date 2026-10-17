@param campfire.view.View v
@param campfire.users.UserPages.MentionUser user
<span class="mention" sgid="${user.attachableSgid()}">@template.users._avatar(v = v, user = user.user()) ${user.user().name()}</span>
