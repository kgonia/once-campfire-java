package campfire.users;

import campfire.view.View;
import campfire.web.Paths;
import org.jspecify.annotations.NullMarked;

/// A user as message and form views show them: the avatar and the author heading.
@NullMarked
public record UserView(long id, String name, String title, String avatarUrl) {
    public static UserView of(User user, View v) {
        return new UserView(user.id(), user.name(), user.title(), v.avatarPath(user));
    }

    public String path() {
        return Paths.user(id);
    }
}
