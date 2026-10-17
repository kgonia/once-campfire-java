package campfire.search;

import campfire.view.Safe;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The search page. `query` is what was searched (non-word characters as spaces), null when
/// nothing was; `q` the parameter as submitted. `returnToRoomId` is where the exit button goes,
/// 0 for a user in no rooms.
@NullMarked
public record SearchPage(@Nullable String query, @Nullable String q, List<Safe> messages, List<String> recentSearches, long returnToRoomId) {}
