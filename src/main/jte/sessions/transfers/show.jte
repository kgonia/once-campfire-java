@import campfire.view.*
@param View v
@param String action
@template.layouts.application(v = v,
  content = @`
${View.formWith(action).method("put").autoSubmit().open()}</form>
`)