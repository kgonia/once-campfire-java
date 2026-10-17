package campfire.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import campfire.rails.HttpFormats;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// `CurrentRequest.parseCookies` splits by hand what Rack splits on `/; */`.
class CookieHeaderTest {
    private static Map<String, String> splitByRegex(String header) {
        var cookies = new LinkedHashMap<String, String>();
        for (String part : Pattern.compile("; *").split(header)) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String name = eq < 0 ? part : part.substring(0, eq);
            if (!cookies.containsKey(name)) cookies.put(name, eq < 0 ? "" : HttpFormats.unescape(part.substring(eq + 1)));
        }
        return cookies;
    }

    @Test
    void matchesRacksSeparator() {
        for (String header : List.of("", "a=1", "a=1; b=2", "a=1;b=2", "a=1;   b=2", " a=1; b=2", "a=1; ", "a=1;", ";a=1", "; ; a=1",
            "a=1; a=2", "flag; b=%41", "a=1 ;b=2", "a=", "=x; b=2", ";;;")) {
            assertEquals(splitByRegex(header), CurrentRequest.parseCookies(header), header);
        }
    }
}
