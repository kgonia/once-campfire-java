@import campfire.view.*
@import gg.jte.Content
@param View v
@param String title = null
@param String bodyClass = null
@param Content head = null
@param Content nav = null
@param Content content
@param Content footer = null
@param Content sidebar = null
@if(v.frameLayout)@template.layouts.frame(head = head, content = content)@else<!DOCTYPE html>
<html>
  <head>
    ${v.pageTitleTag(title)}

    <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no, interactive-widget=resizes-content">
    <meta name="view-transition" content="same-origin">
    <meta name="color-scheme" content="light dark">
    <meta name="theme-color" content="#ffffff" media="(prefers-color-scheme: light)">
    <meta name="theme-color" content="#000000" media="(prefers-color-scheme: dark)">
    <meta name="apple-mobile-web-app-capable" content="yes">
    ${v.currentUserMetaTags()}
    ${v.actionCableMetaTag()}

    ${v.vapidMetaTag()}
    <meta name="turbo-prefetch" content="true">

    <link rel="manifest" href="/webmanifest.json">
    <link rel="icon" href="${v.freshAccountLogoPath()}" type="image/png">
    <link rel="apple-touch-icon" href="${v.freshAccountLogoPath()}">

    ${v.stylesheetTags()}
    ${v.customStylesTag()}

    ${v.importmapTags()}

${head}  </head>

  <body class="${v.bodyClasses(bodyClass)}" data-controller="local-time lightbox">
    <a href="#main-content" class="skip-navigation btn">Skip to main content</a>

    <nav id="nav">
${nav}    </nav>

@if(v.flashNotice != null || v.flashAlert != null)${Safe.from(@`      <div class="flash" data-controller="element-removal" data-action="animationend->element-removal#remove">
        <div class="flash__inner shadow" style="${v.flashAlert != null ? "--flash-background: var(--color-negative)" : ""}">
            ${v.imageTag(v.flashAlert != null ? "alert.svg" : "check.svg", Attrs.of().aria("hidden", "true").set("size", 24).set("class", "colorize--white"))}</span>
        </div>
        <span class="for-screen-reader" role="alert" aria-atomic="true">${v.flashNotice != null ? v.flashNotice : v.flashAlert}</span>
      </div>
`)}@endif
    <main id="main-content">
${content}
      <footer id="footer">
${footer}      </footer>
    </main>

    <aside id="sidebar" data-controller="toggle-class" data-toggle-class-toggle-class="open">
${sidebar}    </aside>

    @template.layouts._lightbox(v = v)

    <a href="https://once.com" id="app-logo" target="_blank" aria-label="Once software from 37signals home page">
      ${v.imageTag("campfire-icon.png", Attrs.of("alt", "Campfire logo", "width", 256, "height", 216))}
    </a>
  </body>
</html>
@endif