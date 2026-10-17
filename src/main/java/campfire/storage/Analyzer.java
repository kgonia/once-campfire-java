package campfire.storage;

import io.quarkus.logging.Log;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// The Active Storage analyzers Campfire runs, in `config.active_storage.analyzers` order:
/// `ImageAnalyzer::Vips`, `VideoAnalyzer`, `AudioAnalyzer` (ImageMagick never accepts with the
/// `:vips` processor), else `NullAnalyzer`. Each returns the metadata it extracts, in Rails' key
/// order; the caller merges it with `analyzed: true`.
public final class Analyzer {
    private Analyzer() {}

    /// How long ffprobe may read a file before it's killed (Rails sets no limit).
    public static final Duration FFPROBE_TIMEOUT = Duration.ofSeconds(30);
    static final Duration VIPSHEADER_TIMEOUT = Duration.ofSeconds(30);

    /// `ImageAnalyzer::Vips#rotated_image?` on libexif's Orientation names.
    private static final List<String> SWAPPED_NAMES = List.of("Right-top", "Left-bottom", "Top-right", "Bottom-left");

    /// `analyzer.metadata` for a local file of the given content type.
    public static Map<String, Object> metadata(String contentType, Path file) {
        String type = contentType == null ? "" : contentType;
        if (type.startsWith("image")) return image(file);
        if (type.startsWith("video")) return video(probe(file));
        if (type.startsWith("audio")) return audio(probe(file));
        return new LinkedHashMap<>();
    }

    // -- Images ------------------------------------------------------------------------------

    /// `ImageAnalyzer::Vips#metadata`: dimensions, swapped for orientations that "rotate". Files
    /// libvips can't read (bmp, ico, psd: their loaders are blocked) yield `{}`, as does every
    /// image without libvips (Rails without ruby-vips).
    static Map<String, Object> image(Path file) {
        var metadata = new LinkedHashMap<String, Object>();
        if (!Tools.VIPSHEADER.available()) return metadata;
        var result = Tools.VIPSHEADER.run(List.of("-a", file.toString()), VIPSHEADER_TIMEOUT, null, Tools.BLOCK_UNTRUSTED);
        if (result.exitCode() != 0) {
            Log.debugf("Skipping image analysis due to a Vips error: %s", result.stderr());
            return metadata;
        }
        Long width = null;
        Long height = null;
        boolean rotated = false;
        for (String line : result.stdoutText().split("\n")) {
            int colon = line.indexOf(": ");
            if (colon < 0) continue;
            String name = line.substring(0, colon).strip();
            String value = line.substring(colon + 2).strip();
            switch (name) {
                case "width" -> width = parseLong(value);
                case "height" -> height = parseLong(value);
                case "exif-ifd0-Orientation" -> rotated = SWAPPED_NAMES.stream().anyMatch(value::contains);
                default -> {}
            }
        }
        if (width == null || height == null) return metadata;
        metadata.put("width", rotated ? height : width);
        metadata.put("height", rotated ? width : height);
        return metadata;
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // -- Video and audio -----------------------------------------------------------------------

    /// `ffprobe -print_format json -show_streams -show_format -v error <path>`; `{}` without ffprobe.
    static Map<?, ?> probe(Path file) {
        Tools.Result result;
        try {
            result = Tools.FFPROBE.run(List.of("-print_format", "json", "-show_streams", "-show_format", "-v", "error", file.toString()),
                FFPROBE_TIMEOUT, null, Map.of());
        } catch (StorageException.Unavailable e) {
            Log.info("Skipping video analysis because FFmpeg isn't installed");
            return Map.of();
        }
        try {
            return Json.decodeValue(result.stdoutText()) instanceof JsonObject probe ? probe.getMap() : Map.of();
        } catch (DecodeException e) {
            throw new StorageException("ffprobe output: " + e.getMessage());
        }
    }

    private static Map<?, ?> stream(Map<?, ?> probe, String codecType) {
        if (probe.get("streams") instanceof List<?> streams) {
            for (Object s : streams) {
                if (s instanceof Map<?, ?> m && codecType.equals(m.get("codec_type"))) return m;
            }
        }
        return Map.of();
    }

    /// `VideoAnalyzer#metadata`: `{ width:, height:, duration:, angle:, display_aspect_ratio:,
    /// audio:, video: }.compact`.
    static Map<String, Object> video(Map<?, ?> probe) {
        Map<?, ?> video = stream(probe, "video");
        Map<?, ?> audio = stream(probe, "audio");

        Long angle = null;
        if (video.get("tags") instanceof Map<?, ?> tags && tags.get("rotate") != null) {
            angle = rubyInteger(tags.get("rotate"));
        } else if (video.get("side_data_list") instanceof List<?> sideData) {
            for (Object d : sideData) {
                if (d instanceof Map<?, ?> m && "Display Matrix".equals(m.get("side_data_type"))) {
                    if (m.get("rotation") != null) angle = rubyInteger(m.get("rotation"));
                    break;
                }
            }
        }

        long[] aspect = null;
        if (video.get("display_aspect_ratio") != null) {
            if (!(video.get("display_aspect_ratio") instanceof String descriptor)) throw new StorageException("display_aspect_ratio");
            String[] terms = descriptor.split(":", 2);
            long numerator = rubyInteger(terms[0]);
            if (terms.length < 2) throw new StorageException("can't convert nil into Integer");
            long denominator = rubyInteger(terms[1]);
            if (numerator != 0) aspect = new long[] {numerator, denominator};
        }

        Double encodedWidth = video.get("width") == null ? null : rubyFloat(video.get("width"));
        Double encodedHeight = video.get("height") == null ? null : rubyFloat(video.get("height"));
        Double computedHeight = encodedWidth != null && aspect != null ? encodedWidth * ((double) aspect[1] / aspect[0]) : null;
        boolean rotated = angle != null && (angle == 90 || angle == 270 || angle == -90 || angle == -270);
        Double width = rotated ? (computedHeight != null ? computedHeight : encodedHeight) : encodedWidth;
        Double height = rotated ? encodedWidth : (computedHeight != null ? computedHeight : encodedHeight);

        Object durationValue = video.get("duration") != null ? video.get("duration")
            : probe.get("format") instanceof Map<?, ?> format ? format.get("duration") : null;
        Double duration = durationValue == null ? null : rubyFloat(durationValue);

        var metadata = new LinkedHashMap<String, Object>();
        if (width != null) metadata.put("width", width);
        if (height != null) metadata.put("height", height);
        if (duration != null) metadata.put("duration", duration);
        if (angle != null) metadata.put("angle", angle);
        if (aspect != null) metadata.put("display_aspect_ratio", List.of(aspect[0], aspect[1]));
        metadata.put("audio", !audio.isEmpty());
        metadata.put("video", !video.isEmpty());
        return metadata;
    }

    /// `AudioAnalyzer#metadata`: `{ duration:, bit_rate:, sample_rate:, tags: }.compact`.
    static Map<String, Object> audio(Map<?, ?> probe) {
        Map<?, ?> audio = stream(probe, "audio");
        var metadata = new LinkedHashMap<String, Object>();
        if (audio.get("duration") != null) metadata.put("duration", rubyFloat(audio.get("duration")));
        if (audio.get("bit_rate") != null) metadata.put("bit_rate", rubyInteger(audio.get("bit_rate")));
        if (audio.get("sample_rate") != null) metadata.put("sample_rate", rubyInteger(audio.get("sample_rate")));
        if (audio.get("tags") != null) metadata.put("tags", audio.get("tags"));
        return metadata;
    }

    /// Ruby's `Float(value)` for the numbers and numeric strings ffprobe emits.
    static double rubyFloat(Object value) {
        return switch (value) {
            case Number n -> n.doubleValue();
            case String s -> {
                try {
                    yield Double.parseDouble(s.strip().replace("_", ""));
                } catch (NumberFormatException e) {
                    throw new StorageException("invalid value for Float(): \"" + s + "\"");
                }
            }
            default -> throw new StorageException("can't convert " + value + " into Float");
        };
    }

    /// Ruby's `Integer(value)`.
    static long rubyInteger(Object value) {
        return switch (value) {
            case Double d when Double.isFinite(d) -> (long) (double) d;
            case Number n -> n.longValue();
            case String s -> {
                try {
                    yield Long.parseLong(s.strip().replace("_", ""));
                } catch (NumberFormatException e) {
                    throw new StorageException("invalid value for Integer(): \"" + s + "\"");
                }
            }
            default -> throw new StorageException("can't convert " + value + " into Integer");
        };
    }
}
