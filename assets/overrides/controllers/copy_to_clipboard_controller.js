import { Controller } from "@hotwired/stimulus"

export default class extends Controller {
  static values = { content: String, url: String }
  static classes = [ "success" ]

  async copy(event) {
    event.preventDefault()
    this.reset()

    try {
      await navigator.clipboard.writeText(this.#text)
      this.element.classList.add(this.successClass)
    } catch {}
  }

  reset() {
    this.element.classList.remove(this.successClass)
    this.#forceReflow()
  }

  // A url value is a path, made absolute against the page it's on, so cached markup that is
  // shared by every request doesn't have to carry the request's host.
  get #text() {
    return this.hasUrlValue ? new URL(this.urlValue, document.baseURI).href : this.contentValue
  }

  #forceReflow() {
    this.element.offsetWidth
  }
}
