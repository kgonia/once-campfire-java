package campfire.rooms;

import campfire.web.CurrentRequest;
import campfire.web.Halt;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The room forms' parameters, checked the way Rails' strong parameters check them.
@NullMarked
final class RoomParams {
    private RoomParams() {}

    /// `room[name]`. `given` is false when the name wasn't submitted as a string, so an update
    /// keeps the old name. `room` must be a non-empty hash (else 400); a non-blank string fails
    /// with a 500, as `permit` on a String does.
    record Name(boolean given, @Nullable String name) {}

    static Name name(CurrentRequest request) {
        Object room = request.params().get("room");
        if (room instanceof String s && !s.isBlank()) throw new IllegalStateException("undefined method 'permit' for an instance of String");
        if (!(room instanceof Map<?, ?> map) || map.isEmpty()) throw new Halt(request.head(400));
        return map.get("name") instanceof String name ? new Name(true, name) : new Name(false, null);
    }

    /// `user_ids[]`: distinct ids in submitted order, ignoring what isn't a number.
    static List<Long> userIds(CurrentRequest request) {
        return request.params().ids("user_ids");
    }
}
