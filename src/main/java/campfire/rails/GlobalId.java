package campfire.rails;

import java.nio.charset.StandardCharsets;

/// A parsed `gid://app/Model/id`. Query params are dropped: the locator ignores them.
public record GlobalId(String app, String modelName, String id) {
    public static GlobalId of(String modelName, Object id) {
        return new GlobalId(Secrets.APP, modelName, String.valueOf(id));
    }

    /// `GlobalID.parse` for the URI form, or null.
    public static GlobalId parse(String gid) {
        if (gid == null || !gid.startsWith("gid://")) return null;
        String rest = gid.substring(6);
        int query = rest.indexOf('?');
        if (query >= 0) rest = rest.substring(0, query);
        int slash = rest.indexOf('/');
        if (slash <= 0) return null;
        String app = rest.substring(0, slash);
        String path = rest.substring(slash + 1);
        int second = path.indexOf('/');
        if (second <= 0 || second == path.length() - 1) return null;
        return new GlobalId(app, path.substring(0, second), path.substring(second + 1));
    }

    /// `GlobalID#to_param`: URL-safe Base64 without padding, as Turbo stream names use.
    public String toParam() {
        return Base64s.urlsafeEncodeUnpadded(toString().getBytes(StandardCharsets.UTF_8));
    }

    public static GlobalId fromParam(String param) {
        byte[] decoded = Base64s.urlsafeDecode(param);
        return decoded == null ? null : parse(new String(decoded, StandardCharsets.UTF_8));
    }

    /// The id as a number, or null.
    public Long longId() {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "gid://" + app + "/" + modelName + "/" + id;
    }
}
