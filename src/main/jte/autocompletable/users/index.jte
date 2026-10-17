@import campfire.view.*
@param View v
@param java.util.List<campfire.users.UserPages.MentionUser> users
@for(var user : users)@template.autocompletable.users._prompt_item(v = v, user = user)@endfor
