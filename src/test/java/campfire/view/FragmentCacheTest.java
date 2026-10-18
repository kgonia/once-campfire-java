package campfire.view;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FragmentCacheTest {
    /// A message fragment renders its boosts' fragments while it is itself being rendered; with
    /// enough of them the cache's map resizes mid-render, which a nested compute can't survive.
    @Test
    void fragmentsRenderOtherFragments() {
        Safe message = FragmentCache.fetch("test/m", () -> {
            var out = new StringBuilder();
            for (int i = 0; i < 200; i++) {
                String boost = "b" + i;
                out.append(FragmentCache.fetch("test/b/" + i, () -> boost.getBytes(UTF_8)));
            }
            return out.toString().getBytes(UTF_8);
        });
        assertEquals("b0b1b2", message.toString().substring(0, 6));
        assertEquals("b7", FragmentCache.read("test/b/7").toString());
    }
}
