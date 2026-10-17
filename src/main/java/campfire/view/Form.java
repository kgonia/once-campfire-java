package campfire.view;

import campfire.richtext.Html;
import java.util.Locale;

/// `form_with(url:, model:, method:, id:, class:, data:)` and its `FormBuilder` fields, in
/// ActionView's attribute order. The form tag is written last ({@link #wrap}) because a
/// `file_field` makes it multipart. No `authenticity_token`: forgery protection is by Fetch Metadata.
public final class Form {
    private final String action;
    private String method = "post";
    private String objectName;
    private String id;
    private String cssClass;
    private final Attrs data = new Attrs();
    private boolean multipart;

    Form(String action) {
        this.action = action;
    }

    /// `model:`, the param key fields are named under ("user", "account").
    public Form model(String paramKey) {
        this.objectName = paramKey;
        return this;
    }

    /// `method:`; a persisted `model:` implies "patch", so pass it for those too.
    public Form method(String method) {
        this.method = method;
        return this;
    }

    public Form id(String id) {
        this.id = id;
        return this;
    }

    public Form cssClass(String cssClass) {
        this.cssClass = cssClass;
        return this;
    }

    public Form data(String key, Object value) {
        data.data(key, value);
        return this;
    }

    /// `auto_submit_form_with`: prepends the auto-submit Stimulus controller.
    @SuppressWarnings("unchecked")
    public Form autoSubmit() {
        Object existing = data.get("data") instanceof java.util.Map<?, ?> m ? ((java.util.Map<String, Object>) m).get("controller") : null;
        data.data("controller", ("auto-submit " + (existing == null ? "" : existing)).strip());
        return this;
    }

    public Form multipart() {
        multipart = true;
        return this;
    }

    /// `<form ...>` plus the `_method` field.
    public Safe open() {
        var html = new Attrs();
        html.set("id", id).set("class", cssClass).merge(data);
        if (multipart) html.set("enctype", "multipart/form-data");
        String m = method.toLowerCase(Locale.ROOT);
        html.set("action", action).set("accept-charset", "UTF-8").set("method", m.equals("get") ? "get" : "post");
        var out = new StringBuilder(160).append("<form");
        html.appendTo(out);
        out.append('>');
        if (!m.equals("get") && !m.equals("post") && !m.isEmpty()) out.append(View.methodTag(m));
        return Safe.of(out.toString());
    }

    /// The whole form around its fields, which render first: a file field makes it multipart.
    public Safe wrap(Object content) {
        Safe fields = Safe.from(content);
        return Safe.concat(open(), fields, Safe.of("</form>"));
    }

    // -- Fields --------------------------------------------------------------------------------------

    public Safe textField(String method, String value, Attrs options) {
        return inputField("text", method, value, options);
    }

    public Safe emailField(String method, String value, Attrs options) {
        return inputField("email", method, value, options);
    }

    public Safe urlField(String method, String value, Attrs options) {
        return inputField("url", method, value, options);
    }

    /// Password fields never render the model's value.
    public Safe passwordField(String method, Attrs options) {
        Attrs merged = Attrs.of("value", null);
        if (options != null) merged.merge(options);
        return inputField("password", method, null, merged);
    }

    public Safe hiddenField(String method, String value, Attrs options) {
        return inputField("hidden", method, value, options);
    }

    public Safe fileField(String method, Attrs options) {
        multipart = true;
        return inputField("file", method, null, options);
    }

    /// `form.fields_for(:settings)`: fields named `object_name[settings][...]` (sharing this form).
    public Form fieldsFor(String name) {
        Form nested = new Form(action);
        nested.objectName = (objectName == null ? "" : objectName) + "[" + name + "]";
        nested.method = method;
        return nested;
    }

    /// `Tags::TextArea#render`: the value is the element's content.
    public Safe textArea(String method, String value, Attrs options) {
        Attrs attrs = options == null ? new Attrs() : options.copy();
        addDefaultNameAndId(method, attrs);
        Object given = attrs.remove("value");
        String content = given != null ? given.toString() : value;
        return Tag.content("textarea", attrs, Safe.of(content == null ? "" : Html.escape(content)));
    }

    /// `form.check_box(method, options, checked_value, unchecked_value)`: a hidden unchecked value,
    /// then the checkbox.
    public Safe checkBox(String method, Attrs options, String checkedValue, String uncheckedValue, String current) {
        Attrs attrs = options == null ? new Attrs() : options.copy();
        attrs.set("type", "checkbox").set("value", checkedValue);
        if (checkedValue.equals(current)) attrs.set("checked", "checked");
        addDefaultNameAndId(method, attrs);
        Attrs hidden = new Attrs();
        for (String key : new String[] {"name", "disabled", "form"}) if (attrs.has(key)) hidden.set(key, attrs.get(key));
        hidden.set("type", "hidden").set("value", uncheckedValue);
        return Safe.concat(Tag.empty("input", hidden), Tag.empty("input", attrs));
    }

    /// `form.button(options) { content }`
    public Safe button(Attrs options, Object content) {
        return View.buttonTag(options, content);
    }

    /// `Tags::TextField#render`
    private Safe inputField(String type, String method, String value, Attrs options) {
        Attrs attrs = options == null ? new Attrs() : options.copy();
        if (!attrs.has("size")) attrs.set("size", attrs.get("maxlength"));
        if (!attrs.has("type")) attrs.set("type", type);
        if (!type.equals("file") && !attrs.has("value")) attrs.set("value", value);
        addDefaultNameAndId(method, attrs);
        return Tag.empty("input", attrs);
    }

    private void addDefaultNameAndId(String method, Attrs attrs) {
        if (!attrs.has("name")) attrs.set("name", objectName == null || objectName.isEmpty() ? method : objectName + "[" + method + "]");
        if (!attrs.has("id")) attrs.set("id", objectName == null || objectName.isEmpty() ? method : sanitizeObjectName(objectName) + "_" + method);
    }

    /// `object_name.gsub(/\]\[|[^-a-zA-Z0-9:.]/, "_").delete_suffix("_")`
    static String sanitizeObjectName(String name) {
        var out = new StringBuilder();
        for (char c : name.replace("][", "_").toCharArray()) {
            out.append(c < 128 && (Character.isLetterOrDigit(c) || c == '-' || c == ':' || c == '.') ? c : '_');
        }
        if (out.length() > 0 && out.charAt(out.length() - 1) == '_') out.setLength(out.length() - 1);
        return out.toString();
    }
}
