package campfire.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;

import campfire.rails.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Against what `assets:precompile` produced in the reference image (without the port's overrides).
class AssetCompilerTest {
    static final Path PROJECT = Path.of("").toAbsolutePath();

    static AssetCompiler referenceCompiler() throws Exception {
        Path vendor = PROJECT.resolve("assets/vendor");
        var loadPath = new ArrayList<Path>();
        for (String line : Files.readAllLines(vendor.resolve("LOAD_PATH"))) {
            if (line.isBlank()) continue;
            int colon = line.indexOf(':');
            loadPath.add(line.startsWith("reference:") ? PROJECT.resolve("reference").resolve(line.substring(colon + 1)) : vendor.resolve(line.substring(colon + 1)));
        }
        return new AssetCompiler(loadPath, "1.0");
    }

    static String resource(String name) throws Exception {
        try (var in = AssetCompilerTest.class.getResourceAsStream("/assets-reference/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void digestsAndCompilesLikePropshaft() throws Exception {
        var compiler = referenceCompiler();
        var manifest = (Map<String, Map<String, Object>>) Json.parse(resource("manifest.json"));
        var compiled = (Map<String, String>) Json.parse(resource("compiled_sha256.json"));
        int checked = 0;
        for (var entry : manifest.entrySet()) {
            Integer index = compiler.find(entry.getKey());
            String digested = (String) entry.getValue().get("digested_path");
            assertEquals(digested, index == null ? null : compiler.digestedPath(index), entry.getKey());
            byte[] body = compiler.compiledContent(index);
            if (body == null) body = Files.readAllBytes(compiler.assetSource(index));
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            if (compiled.containsKey(digested)) {
                assertEquals(compiled.get(digested), sha, digested);
                checked++;
            }
        }
        System.out.println("checked " + checked + " of " + manifest.size() + " assets");
    }

    @Test
    void rendersImportmapTags() throws Exception {
        var compiler = referenceCompiler();
        var pins = AssetCompiler.importmap(PROJECT.resolve("reference/config/importmap.rb"), PROJECT.resolve("reference"));
        assertEquals(resource("javascript_importmap_tags.html").strip(), compiler.importmapTags(pins).strip());
    }
}
