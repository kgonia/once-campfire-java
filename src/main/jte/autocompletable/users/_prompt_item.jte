@param campfire.view.View v
@param campfire.users.UserPages.MentionUser user
<lexxy-prompt-item search="${user.user().name()}" sgid="${user.attachableSgid()}">
  <template type="menu">
    <span class="autocomplete__item flex align-center gap unpad">
      @template.users._avatar(v = v, user = user.user())
      <span class="autocompletable__name">${user.user().name()}</span>
    </span>
  </template>
  <template type="editor">
    @template.users._mention(v = v, user = user)
  </template>
</lexxy-prompt-item>
