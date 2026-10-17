package campfire.cable;

/// Broadcasting names: `Channel::Naming#channel_name` and `Channel::Broadcasting#broadcasting_for`.
/// Callers pass each broadcastable already converted with `to_gid_param` (records) or `to_param`
/// (strings and symbols, which are themselves).
final class Naming {
    private Naming() {}

    /// `name.delete_suffix("Channel").gsub("::", ":").underscore`
    static String channelName(String className) {
        String name = className.endsWith("Channel") ? className.substring(0, className.length() - "Channel".length()) : className;
        return underscore(name.replace("::", ":"));
    }

    /// The channel name followed by each broadcastable, joined with `:`. `stream_for @room` in
    /// `RoomChannel` streams from `room:<gid param>`.
    static String broadcastingFor(String className, String... broadcastables) {
        var out = new StringBuilder(channelName(className));
        for (String b : broadcastables) out.append(':').append(b);
        return out.toString();
    }

    /// `ActiveSupport::Inflector.underscore` without acronym inflections (Campfire defines none).
    static String underscore(String word) {
        String s = word.replace("::", "/");
        var out = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z' && i > 0) {
                char prev = s.charAt(i - 1);
                char next = i + 1 < s.length() ? s.charAt(i + 1) : 0;
                boolean lowerBefore = (prev >= 'a' && prev <= 'z') || (prev >= '0' && prev <= '9');
                boolean acronymEnd = ((prev >= 'A' && prev <= 'Z') || (prev >= '0' && prev <= '9')) && next >= 'a' && next <= 'z';
                if (lowerBefore || acronymEnd) out.append('_');
            }
            out.append(c == '-' ? '_' : Character.toLowerCase(c));
        }
        return out.toString();
    }
}
