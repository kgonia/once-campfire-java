@param campfire.view.View v
<dialog class="lightbox" aria-label="Image Viewer (Press escape to close)" data-lightbox-target="dialog" data-action="close->lightbox#reset">
  <img src="" class="lightbox__image" data-lightbox-target="zoomedImage">

  <form method="dialog" class="lightbox__btn">
    <button class="btn">
      <img aria-hidden="true" src="${v.asset("remove.svg")}">
      <span class="for-screen-reader">Close image viewer</span>
    </button>
  </form>

  <a href="" class="lightbox__btn--download btn hide-in-ios-pwa" data-lightbox-target="download">
    <img aria-hidden="true" src="${v.asset("download.svg")}">
    <span class="for-screen-reader">Download file</span>
  </a>

  <button class="lightbox__btn--share btn"
      data-controller="web-share"
      data-action="web-share#share"
      data-web-share-files-value=""
      data-lightbox-target="share">
    <img aria-hidden="true" src="${v.asset("share.svg")}">
    <span class="for-screen-reader">Share file</span>
  </button>
</dialog>
