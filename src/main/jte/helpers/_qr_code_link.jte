@import campfire.view.View
@param String url
@param gg.jte.Content content
!{String path = View.qrCodePath(url);}
<a class="btn" data-lightbox-target="image" data-action="lightbox#open" data-lightbox-url-value="${path}" href="${path}">
  ${content}
</a>
