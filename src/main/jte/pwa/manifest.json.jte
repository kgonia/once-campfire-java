@param campfire.view.View v
@param String name
@param String smallLogo
@param String logo
{
  "name": "${name}",
  "icons": [
    {
      "src": "${smallLogo}",
      "type": "image/png",
      "sizes": "192x192"
    },
    {
      "src": "${logo}",
      "type": "image/png",
      "sizes": "512x512"
    },
    {
      "src": "${logo}",
      "type": "image/png",
      "sizes": "512x512",
      "purpose": "maskable"
    }
  ],
  "start_url": "/",
  "display": "standalone",
  "scope": "/",
  "description": "A chat app from the makers of Basecamp and HEY.",
  "categories": ["social", "business", "productivity"],
  "theme_color": "#ffffff",
  "background_color": "#ffffff",
  "shortcuts": [
    {
      "name": "New chat room",
      "description": "Open Campfire and start a new chat room",
      "url": "rooms/opens/new",
      "icons": [{ "src": "${v.url(v.asset("add.svg"))}", "sizes": "any" }]
    },
    {
      "name": "My profile",
      "description": "Open Campfire and view your profile",
      "url": "/users/me/profile",
      "icons": [{ "src": "${v.url(v.asset("person.svg"))}", "sizes": "any" }]
    }
  ],
  "screenshots": [
    {
      "src": "${v.url(v.asset("screenshots/android-chat.png"))}",
      "sizes": "1080x2400",
      "form_factor": "narrow",
      "label": "Campfire is an installable, self-hosted group chat system."
    },
    {
      "src": "${v.url(v.asset("screenshots/android-sidebar.png"))}",
      "sizes": "1080x2400",
      "form_factor": "narrow",
      "label": "Easily invite people. Make rooms. ${"@"}mentions, DMs, and mobile support."
    },
    {
      "src": "${v.url(v.asset("screenshots/android-dark-mode.png"))}",
      "sizes": "1080x2400",
      "form_factor": "narrow",
      "label": "Full support for dark mode, customizable to your brand."
    }
  ]
}
