@import campfire.view.*
@param View v
@param campfire.users.UserPages.MentionUser user
<lexxy-prompt-item search="${user.user().name()}" sgid="${user.attachableSgid()}">
  <template type="menu">
    <span class="autocomplete__item flex align-center gap unpad">
      ${v.avatarTag(user.user(), Attrs.of())}
      <span class="autocompletable__name">${user.user().name()}</span>
    </span>
  </template>
  <template type="editor">
    @template.users._mention(v = v, user = user)
  </template>
</lexxy-prompt-item>
