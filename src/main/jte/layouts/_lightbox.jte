@import campfire.view.*
@import campfire.web.Paths
@param View v
<dialog class="lightbox" aria-label="Image Viewer (Press escape to close)" data-lightbox-target="dialog" data-action="close->lightbox#reset">
  <img src="" class="lightbox__image" data-lightbox-target="zoomedImage" />

  <form method="dialog" class="lightbox__btn">
    <button class="btn">
      ${v.imageTag("remove.svg", Attrs.of().aria("hidden", "true"))}
      <span class="for-screen-reader">Close image viewer</span>
    </button>
  </form>

  <a href="" class="lightbox__btn--download btn hide-in-ios-pwa" data-lightbox-target="download">
    ${v.imageTag("download.svg", Attrs.of().aria("hidden", "true"))}
    <span class="for-screen-reader">Download file</span>
  </a>

  <button class="lightbox__btn--share btn"
      data-controller="web-share"
      data-action="web-share#share"
      data-web-share-files-value=""
      data-lightbox-target="share">
    ${v.imageTag("share.svg", Attrs.of().aria("hidden", "true"))}
    <span class="for-screen-reader">Share file</span>
  </button>
</dialog>
