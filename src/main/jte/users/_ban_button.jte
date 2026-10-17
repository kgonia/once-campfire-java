@import campfire.view.*
@import campfire.web.Paths
@param View v
@param campfire.users.User user
@if(user.isActive())  ${View.buttonTo(Paths.userBan(user.id()), Attrs.of("method", "post", "class", "btn full-width").data("turbo_confirm", "Are you sure you want to ban this user? This will log them out, delete their messages, and block their IP addresses."), @`
    ${v.imageTag("cancel.svg", Attrs.of().aria("hidden", "true", "label", "Ban " + user.name()))}
    <span>Ban ${user.name()}</span>
`)}@else  ${View.buttonTo(Paths.userBan(user.id()), Attrs.of("method", "delete", "class", "btn btn--negative full-width").data("turbo_confirm", "Are you sure you want to remove the ban on this user?"), @`
    ${v.imageTag("cancel.svg", Attrs.of().aria("hidden", "true", "label", "Remove Ban " + user.name()))}
    <span>Remove ban</span>
`)}@endif