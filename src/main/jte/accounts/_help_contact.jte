@import campfire.accounts.HelpContact
@import campfire.view.*
@param View v
@param HelpContact helpContact
@if(helpContact != null)  <div class="txt-align-center margin-block-double full-width">
    ${View.linkTo("mailto:\"" + helpContact.name() + "\" <" + helpContact.emailAddress() + ">", Attrs.of("class", "btn center", "title", "Email " + helpContact.name()), @`
      ${v.imageTag("lifebuoy.svg", Attrs.of().aria("hidden", "true"))}
      <span>${helpContact.emailAddress()}</span>
`)}
    <div class="txt-align-center center margin-block txt-subtle">Campfire&trade; version ${v.versionBadge()}</div>
  </div>
@endif