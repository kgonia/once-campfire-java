@import campfire.accounts.HelpContact
@param campfire.view.View v
@param HelpContact helpContact
@if(helpContact != null)
  <div class="txt-align-center margin-block-double full-width">
    <a class="btn center" title="Email ${helpContact.name()}" href="${"mailto:\"" + helpContact.name() + "\" <" + helpContact.emailAddress() + ">"}">
      <img aria-hidden="true" src="${v.asset("lifebuoy.svg")}">
      <span>${helpContact.emailAddress()}</span>
    </a>
    <div class="txt-align-center center margin-block txt-subtle">Campfire&trade; version <span class="version-badge">${v.appVersion()}</span></div>
  </div>
@endif
