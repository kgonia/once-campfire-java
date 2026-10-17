package campfire.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Producing variant and preview images with the libvips and ffmpeg CLIs:
/// `ActiveStorage::Transformers::Vips` (image_processing 1.14) and
/// `ActiveStorage::Previewer::VideoPreviewer`.
///
/// image_processing loads the first page, autorotates, then for `resize_to_limit` runs
/// `thumbnail_image(w, height: h, size: :down)` and sharpens with `conv(SHARPEN_MASK, precision:
/// :integer)`, saving with the format's defaults. The CLI equivalent is `vips thumbnail` (which
/// autorotates and reads page 0) into a lossless `.v` file, then `vips conv` with the same mask
/// into the output; formats are converted by `vips autorot`. Dimensions and metadata match;
/// bytes may differ slightly because `vips thumbnail` shrinks on load.
public final class Transformer {
    private Transformer() {}

    /// How long ffmpeg may take to draw a poster frame (Rails sets no limit).
    public static final Duration FFMPEG_TIMEOUT = Duration.ofSeconds(60);
    static final Duration VIPS_TIMEOUT = Duration.ofSeconds(120);

    /// `ImageProcessing::Vips::Processor::SHARPEN_MASK`: `[[-1,-1,-1],[-1,32,-1],[-1,-1,-1]]`, scale 24.
    static final String SHARPEN_MASK = "3 3 24 0\n-1 -1 -1\n-1 32 -1\n-1 -1 -1\n";

    /// `ActiveStorage.video_preview_arguments` (`load_defaults 7.0`), already shell-split.
    public static final List<String> VIDEO_PREVIEW_ARGUMENTS =
        List.of("-vf", "select=eq(n\\,0)+eq(key\\,1)+gt(scene\\,0.015),loop=loop=-1:size=2,trim=start_frame=1", "-frames:v", "1", "-f", "image2");

    /// `variation.transform(file)`: writes the variant to a new file in `workDir`, named for the
    /// format, and returns it.
    public static Path transform(Path input, Variation variation, Path workDir) {
        String format = variation.format().toLowerCase(Locale.ROOT);
        List<Integer[]> operations = variation.resizeOperations();
        if (!Tools.VIPS.available()) throw new StorageException.Unavailable("libvips (vips) is not installed");
        Path output = workDir.resolve("image_processing." + format);
        if (operations.isEmpty()) {
            Tools.VIPS.runOrFail(List.of("autorot", input.toString(), output.toString()), VIPS_TIMEOUT, null, Tools.BLOCK_UNTRUSTED);
            return output;
        }
        try {
            Path mask = workDir.resolve("sharpen.mat");
            Files.writeString(mask, SHARPEN_MASK);
            Path current = input;
            for (int i = 0; i < operations.size(); i++) {
                Integer[] size = operations.get(i);
                Path thumbnail = workDir.resolve("thumbnail-" + i + ".v");
                var args = new ArrayList<>(List.of("thumbnail", current.toString(), thumbnail.toString(), dimension(size[0]),
                    "--height", dimension(size[1]), "--size", "down"));
                // Only the upload itself is untrusted input (`Vips.block_untrusted(true)`).
                Tools.VIPS.runOrFail(args, VIPS_TIMEOUT, null, i == 0 ? Tools.BLOCK_UNTRUSTED : Map.of());
                Path sharpened = i == operations.size() - 1 ? output : workDir.resolve("sharpened-" + i + ".v");
                Tools.VIPS.runOrFail(List.of("conv", thumbnail.toString(), sharpened.toString(), mask.toString(), "--precision", "integer"),
                    VIPS_TIMEOUT, null, Map.of());
                current = sharpened;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return output;
    }

    /// `::Vips::MAX_COORD` for an unbounded side, as image_processing's `default_dimensions`.
    private static String dimension(Integer value) {
        return Integer.toString(value == null ? 10_000_000 : value);
    }

    /// `VideoPreviewer.accept?`: a video and `ffmpeg -version` works.
    public static boolean canPreview(String contentType) {
        return contentType != null && contentType.startsWith("video") && Tools.FFMPEG.available();
    }

    /// `draw_relevant_frame_from`: `ffmpeg -i <input> <video_preview_arguments> -`, stdout into a
    /// JPEG file in `workDir`.
    public static Path videoPreview(Path input, Path workDir) {
        Path output = workDir.resolve("preview.jpg");
        var args = new ArrayList<String>();
        args.add("-i");
        args.add(input.toString());
        args.addAll(VIDEO_PREVIEW_ARGUMENTS);
        args.add("-");
        Tools.FFMPEG.runOrFail(args, FFMPEG_TIMEOUT, output, Map.of());
        try {
            if (Files.size(output) == 0) throw new StorageException("ffmpeg drew no frame");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return output;
    }
}
