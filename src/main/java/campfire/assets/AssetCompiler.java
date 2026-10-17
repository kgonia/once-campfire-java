package campfire.assets;

import campfire.richtext.Html;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/// Build step: digests the Rails frontend exactly as Propshaft 1.2's `assets:precompile` does
/// (SHA1 of the content, everything it references and the assets version), rewrites asset URLs in
/// CSS and JavaScript, and renders the importmap tags importmap-rails 2.2 would. Port-owned files
/// in `assets/overrides` shadow the reference's by logical path.
///
/// Writes `public/assets/<digested path>` (and a `.gz` beside each compressible file), the
/// reference's `public/` files, and `campfire/assets/manifest.tsv`, `importmap.html` and
/// `stylesheets.txt` for the app to load at boot.
public final class AssetCompiler {
    private static final String PREFIX = "/assets";

    record Asset(String logicalPath, Path source, byte[] content) {}

    private final List<Asset> assets = new ArrayList<>();
    private final Map<String, Integer> byLogicalPath = new LinkedHashMap<>();
    private final String version;
    private final Map<Integer, String> digests = new java.util.HashMap<>();

    // Ruby's \s spelled out, so the Latin-1 decoded input can't match more.
    private static final String WS = "[ \\t\\n\\x0B\\f\\r]";
    private static final Pattern CSS_ASSET_URLS = quotedUrl("url", "\\#|%23|data:|http:|https:|//");
    private static final Pattern JS_ASSET_URLS = quotedUrl("RAILS_ASSET_URL", "\\#|%23|data|http|//");
    private static final Pattern SOURCE_MAPPING_URLS = Pattern.compile("(//|/\\*)# sourceMappingURL=(.+\\.map)(" + WS + "*?\\*/)?" + WS + "*?(?=\\n?\\z)");
    private static final Pattern ALREADY_DIGESTED = Pattern.compile("-([0-9a-zA-Z_-]{7,128})\\.digested");
    private final Pattern urlPrefixInSourceMap = Pattern.compile("(?m)^(.+/)?" + Pattern.quote("assets") + "/");

    private static Pattern quotedUrl(String head, String excluded) {
        return Pattern.compile(head + "\\(" + WS + "*[\"']?(?!(?:" + excluded + "))([^\"' \\t\\n\\x0B\\f\\r?#)]+)([#?][^\"')]+)?" + WS + "*[\"']?\\)");
    }

    AssetCompiler(List<Path> loadPath, String version) throws IOException {
        this.version = version;
        for (Path dir : loadPath) {
            if (!Files.isDirectory(dir)) continue;
            List<Path> files;
            try (Stream<Path> walk = Files.walk(dir)) {
                files = walk.filter(Files::isRegularFile).sorted(componentwise(dir)).toList();
            }
            for (Path file : files) {
                if (file.getFileName().toString().startsWith(".")) continue;
                String logical = dir.relativize(file).toString().replace('\\', '/');
                if (byLogicalPath.containsKey(logical)) continue;
                byLogicalPath.put(logical, assets.size());
                assets.add(new Asset(logical, file, Files.readAllBytes(file)));
            }
        }
    }

    /// Byte-wise order of path components, as Rust's PathBuf and Ruby's sorted globs compare.
    static java.util.Comparator<Path> componentwise(Path root) {
        return (a, b) -> {
            String[] x = root.relativize(a).toString().replace(java.io.File.separatorChar, '/').split("/");
            String[] y = root.relativize(b).toString().replace(java.io.File.separatorChar, '/').split("/");
            for (int i = 0; i < Math.min(x.length, y.length); i++) {
                int c = java.util.Arrays.compare(x[i].getBytes(StandardCharsets.UTF_8), y[i].getBytes(StandardCharsets.UTF_8));
                if (c != 0) return c;
            }
            return Integer.compare(x.length, y.length);
        };
    }

    Path assetSource(int index) {
        return assets.get(index).source();
    }

    Integer find(String logicalPath) {
        return byLogicalPath.get(logicalPath);
    }

    private static String extname(String path) {
        String base = path.substring(path.lastIndexOf('/') + 1);
        String trimmed = base.replaceFirst("^\\.+", "");
        int dot = trimmed.lastIndexOf('.');
        return dot < 0 ? "" : trimmed.substring(dot);
    }

    private Pattern pattern(int index) {
        return switch (extname(assets.get(index).logicalPath())) {
            case ".css" -> CSS_ASSET_URLS;
            case ".js" -> JS_ASSET_URLS;
            default -> null;
        };
    }

    String digest(int index) {
        return digests.computeIfAbsent(index, i -> {
            try {
                var sha1 = MessageDigest.getInstance("SHA-1");
                sha1.update(assets.get(i).content());
                for (int referenced : referencedBy(i)) sha1.update(assets.get(referenced).content());
                sha1.update(version.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(sha1.digest()).substring(0, 8);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    String digestedPath(int index) {
        String logical = assets.get(index).logicalPath();
        if (ALREADY_DIGESTED.matcher(logical).find()) return logical;
        int dot = digestableExtensionStart(logical);
        return dot < 0 ? logical : logical.substring(0, dot) + "-" + digest(index) + logical.substring(dot);
    }

    /// The start of what `sub(/\.(\w+(\.map)?)$/)` replaces.
    private static int digestableExtensionStart(String path) {
        for (int dot = path.indexOf('.'); dot >= 0; dot = path.indexOf('.', dot + 1)) {
            String rest = path.substring(dot + 1);
            if (isWord(rest) || (rest.endsWith(".map") && isWord(rest.substring(0, rest.length() - 4)))) return dot;
        }
        return -1;
    }

    private static boolean isWord(String s) {
        return !s.isEmpty() && s.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_');
    }

    private List<Integer> referencedBy(int index) {
        var references = new ArrayList<Integer>();
        Pattern pattern = pattern(index);
        if (pattern != null) collectReferences(index, pattern, references);
        return references;
    }

    private void collectReferences(int index, Pattern pattern, List<Integer> references) {
        String content = latin1(assets.get(index).content());
        String directory = dirname(assets.get(index).logicalPath());
        Matcher m = pattern.matcher(content);
        while (m.find()) {
            Integer referenced = find(resolvePath(directory, toUtf8(m.group(1))));
            if (referenced != null && !references.contains(referenced)) {
                references.add(referenced);
                collectReferences(referenced, pattern, references);
            }
        }
    }

    byte[] compiledContent(int index) {
        Pattern pattern = pattern(index);
        if (pattern == null) return null;
        Asset asset = assets.get(index);
        boolean css = pattern == CSS_ASSET_URLS;
        String input = latin1(asset.content());
        String directory = dirname(asset.logicalPath());
        String withUrls = replaceAll(pattern, input, m -> {
            String url = toUtf8(m.group(1));
            String fingerprint = m.group(2) == null ? "" : toUtf8(m.group(2));
            Integer found = find(resolvePath(directory, url));
            String replacement = found != null ? "\"" + PREFIX + "/" + digestedPath(found) + fingerprint + "\"" : "\"" + url + "\"";
            return fromUtf8(css ? "url(" + replacement + ")" : replacement);
        });
        String output = replaceAll(SOURCE_MAPPING_URLS, withUrls, m -> {
            String commentStart = m.group(1);
            String commentEnd = m.group(3) == null ? "" : m.group(3);
            String url = urlPrefixInSourceMap.matcher(toUtf8(m.group(2))).replaceAll("");
            String resolved = directory.equals(".") ? url : plus(directory, url);
            Integer found = find(resolved);
            return found != null ? commentStart + "# sourceMappingURL=" + PREFIX + "/" + fromUtf8(digestedPath(found)) + commentEnd : commentStart + commentEnd;
        });
        return output.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String replaceAll(Pattern pattern, String input, java.util.function.Function<Matcher, String> replace) {
        Matcher m = pattern.matcher(input);
        var out = new StringBuilder(input.length());
        int last = 0;
        while (m.find()) {
            out.append(input, last, m.start()).append(replace.apply(m));
            last = m.end();
        }
        return out.append(input, last, input.length()).toString();
    }

    private static String latin1(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static String toUtf8(String latin1) {
        return new String(latin1.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    private static String fromUtf8(String utf8) {
        return new String(utf8.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    private static String dirname(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "." : path.substring(0, slash);
    }

    private static String resolvePath(String directory, String filename) {
        if (filename.startsWith("../")) return cleanpath(plus(directory, filename));
        if (filename.startsWith("/")) return filename.substring(1);
        return plus(directory, filename.startsWith("./") ? filename.substring(2) : filename);
    }

    /// `Pathname#+` for relative paths.
    private static String plus(String left, String right) {
        var prefix = new ArrayList<>(List.of(left.split("/"))).stream().filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        var suffix = new ArrayList<>(List.of(right.split("/"))).stream().filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        var kept = new ArrayList<String>();
        while (true) {
            while (!suffix.isEmpty() && suffix.getFirst().equals(".")) suffix.removeFirst();
            if (prefix.isEmpty()) break;
            String last = prefix.removeLast();
            if (last.equals(".")) continue;
            if (last.equals("..") || suffix.isEmpty() || !suffix.getFirst().equals("..")) {
                kept.add(last);
                break;
            }
            suffix.removeFirst();
        }
        prefix.addAll(kept);
        prefix.addAll(suffix);
        return prefix.isEmpty() ? "." : String.join("/", prefix);
    }

    private static String cleanpath(String path) {
        var out = new ArrayList<String>();
        for (String component : path.split("/")) {
            if (component.isEmpty() || component.equals(".")) continue;
            if (component.equals("..") && !out.isEmpty() && !out.getLast().equals("..")) out.removeLast();
            else out.add(component);
        }
        return out.isEmpty() ? "." : String.join("/", out);
    }

    // -- importmap-rails ------------------------------------------------------------------------

    record Pin(String name, String path, boolean preload) {}

    /// `Importmap::Map#expanded_packages_and_directories` for `pin` and `pin_all_from`.
    static List<Pin> importmap(Path importmapRb, Path railsRoot) throws IOException {
        var packages = new LinkedHashMap<String, Pin>();
        var directories = new LinkedHashMap<String, String[]>();
        Pattern string = Pattern.compile("[\"']([^\"']*)[\"']");
        for (String raw : Files.readAllLines(importmapRb)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int hash = line.indexOf(" #");
            if (hash >= 0) line = line.substring(0, hash).strip();
            String command = line.split("\\s+", 2)[0];
            Matcher m = string.matcher(line);
            if (!m.find()) continue;
            String first = m.group(1);
            String to = option(line, "to"), under = option(line, "under");
            boolean preload = !line.matches(".*preload:\\s*false.*");
            if (command.equals("pin")) {
                packages.remove(first);
                packages.put(first, new Pin(first, to != null ? to : first + ".js", preload));
            } else if (command.equals("pin_all_from")) {
                directories.remove(first);
                directories.put(first, new String[] {under, to, String.valueOf(preload)});
            }
        }
        for (var entry : directories.entrySet()) {
            Path root = railsRoot.resolve(entry.getKey());
            if (!Files.isDirectory(root)) continue;
            String under = entry.getValue()[0], to = entry.getValue()[1];
            boolean preload = Boolean.parseBoolean(entry.getValue()[2]);
            List<Path> files;
            try (Stream<Path> walk = Files.walk(root)) {
                files = walk.filter(Files::isRegularFile).filter(p -> !hasDotComponent(root.relativize(p)))
                    .filter(p -> p.toString().endsWith(".js") || p.toString().endsWith(".jsm")).sorted(componentwise(root)).toList();
            }
            for (Path file : files) {
                String filename = root.relativize(file).toString().replace('\\', '/');
                String stem = filename.substring(0, filename.length() - extname(filename).length());
                if (stem.equals("index")) stem = "";
                else if (stem.endsWith("/index")) stem = stem.substring(0, stem.length() - 6);
                String name = Stream.of(under, stem.isEmpty() ? null : stem).filter(s -> s != null && !s.isEmpty())
                    .collect(java.util.stream.Collectors.joining("/"));
                String base = to != null ? to : under;
                String path = Stream.of(base, filename).filter(s -> s != null && !s.isEmpty()).collect(java.util.stream.Collectors.joining("/"));
                packages.put(name, new Pin(name, path, preload));
            }
        }
        return new ArrayList<>(packages.values());
    }

    private static boolean hasDotComponent(Path relative) {
        for (Path part : relative) if (part.toString().startsWith(".")) return true;
        return false;
    }

    private static String option(String line, String key) {
        Matcher m = Pattern.compile("\\b" + key + ":\\s*[\"']([^\"']*)[\"']").matcher(line);
        return m.find() ? m.group(1) : null;
    }

    String importmapTags(List<Pin> pins) {
        var imports = new ArrayList<String>();
        var preloads = new ArrayList<String>();
        for (Pin pin : pins) {
            Integer index = find(pin.path());
            if (index == null) continue;
            String path = PREFIX + "/" + digestedPath(index);
            imports.add("    " + campfire.rails.Json.generate(pin.name()) + ": " + campfire.rails.Json.generate(path));
            if (pin.preload() && !preloads.contains(path)) preloads.add(path);
        }
        String json = imports.isEmpty() ? "{\n  \"imports\": {}\n}" : "{\n  \"imports\": {\n" + String.join(",\n", imports) + "\n  }\n}";
        var tags = new ArrayList<String>();
        tags.add("<script type=\"importmap\" data-turbo-track=\"reload\">" + json + "</script>");
        tags.add(String.join("\n", preloads.stream().map(p -> "<link rel=\"modulepreload\" href=\"" + Html.escape(p) + "\">").toList()));
        tags.add("<script type=\"module\">import \"application\"</script>");
        return String.join("\n", tags);
    }

    // -- Build ---------------------------------------------------------------------------------

    private static final java.util.Set<String> COMPRESSIBLE = java.util.Set.of(".css", ".js", ".svg", ".map", ".json", ".txt", ".html", ".ico");

    public static void main(String[] args) throws IOException {
        Path project = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Path railsRoot = project.resolve("reference");
        Path vendor = project.resolve("assets/vendor");
        var loadPath = new ArrayList<Path>();
        loadPath.add(project.resolve("assets/overrides"));
        for (String line : Files.readAllLines(vendor.resolve("LOAD_PATH"))) {
            if (line.isBlank()) continue;
            int colon = line.indexOf(':');
            String kind = line.substring(0, colon), dir = line.substring(colon + 1);
            loadPath.add(kind.equals("reference") ? railsRoot.resolve(dir) : vendor.resolve(dir));
        }
        var compiler = new AssetCompiler(loadPath, assetsVersion(railsRoot));

        Path publicDir = output.resolve("public");
        Path assetsDir = publicDir.resolve("assets");
        var manifest = new StringBuilder();
        var stylesheets = new ArrayList<String>();
        for (int i = 0; i < compiler.assets.size(); i++) {
            Asset asset = compiler.assets.get(i);
            String digested = compiler.digestedPath(i);
            byte[] compiled = compiler.compiledContent(i);
            byte[] body = compiled != null ? compiled : asset.content();
            Path target = assetsDir.resolve(digested);
            Files.createDirectories(target.getParent());
            Files.write(target, body);
            if (COMPRESSIBLE.contains(extname(digested)) && body.length > 256) Files.write(Path.of(target + ".gz"), gzip(body));
            manifest.append(asset.logicalPath()).append('\t').append(digested).append('\n');
            if (extname(asset.logicalPath()).equals(".css")) stylesheets.add(asset.logicalPath());
        }
        stylesheets.sort(null);

        Path reference = railsRoot.resolve("public");
        if (Files.isDirectory(reference)) {
            try (Stream<Path> walk = Files.walk(reference)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    Path relative = reference.relativize(file);
                    if (relative.startsWith("assets")) continue;
                    Path target = publicDir.resolve(relative.toString());
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        var manifestJson = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < compiler.assets.size(); i++) {
            var entry = new java.util.LinkedHashMap<String, Object>();
            entry.put("digested_path", compiler.digestedPath(i));
            entry.put("integrity", null);
            manifestJson.put(compiler.assets.get(i).logicalPath(), entry);
        }
        Files.writeString(assetsDir.resolve(".manifest.json"), campfire.rails.Json.generate(manifestJson));

        Path meta = output.resolve("campfire/assets");
        Files.createDirectories(meta);
        Files.writeString(meta.resolve("manifest.tsv"), manifest);
        Files.writeString(meta.resolve("stylesheets.txt"), String.join("\n", stylesheets) + "\n");
        Files.writeString(meta.resolve("importmap.html"), compiler.importmapTags(importmap(railsRoot.resolve("config/importmap.rb"), railsRoot)));
        var files = new StringBuilder();
        try (Stream<Path> walk = Files.walk(publicDir)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) files.append('/').append(publicDir.relativize(file).toString().replace('\\', '/')).append('\n');
        }
        Files.writeString(meta.resolve("files.txt"), files);
    }

    private static String assetsVersion(Path railsRoot) throws IOException {
        Path initializer = railsRoot.resolve("config/initializers/assets.rb");
        if (!Files.exists(initializer)) return "1";
        for (String line : Files.readAllLines(initializer)) {
            if (line.strip().startsWith("#")) continue;
            int at = line.indexOf("config.assets.version");
            if (at < 0) continue;
            String value = line.substring(at + "config.assets.version".length()).strip();
            if (value.startsWith("=")) return value.substring(1).strip().replaceAll("^[\"']|[\"']$", "");
        }
        return "1";
    }

    private static byte[] gzip(byte[] body) throws IOException {
        var out = new ByteArrayOutputStream(body.length / 3 + 64);
        try (var gz = new GZIPOutputStream(out) {
            {
                def.setLevel(9);
            }
        }) {
            gz.write(body);
        }
        return out.toByteArray();
    }
}
