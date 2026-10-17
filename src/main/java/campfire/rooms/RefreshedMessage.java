package campfire.rooms;

import campfire.view.Safe;
import org.jspecify.annotations.NullMarked;

/// A message edited since the client last looked, as a room refresh replaces it: its dom id and
/// its partial.
@NullMarked
public record RefreshedMessage(String target, Safe html) {}
