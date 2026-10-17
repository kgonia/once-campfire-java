@param campfire.view.View v
@param String action
@template.layouts.application(v = v, content = @`
  <form data-controller="auto-submit" action="${action}" accept-charset="UTF-8" method="post">
    <input type="hidden" name="_method" value="put">
  </form>
`)
