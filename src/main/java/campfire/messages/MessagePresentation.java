package campfire.messages;

import campfire.messages.MessageContent.Attachment;
import campfire.messages.MessageContent.Sound;
import campfire.messages.MessageContent.Text;
import campfire.messages.MessageContent.Unrenderable;
import campfire.view.Safe;
import campfire.view.View;
import java.math.BigDecimal;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import static campfire.richtext.Html.escape;

/// The HTML of a message body: rich text as is, a sound's play button, or an attachment's
/// preview or download link. Attachment previews are sized to fit 1200×800, keeping the aspect ratio.
@NullMarked
public final class MessagePresentation {
    private MessagePresentation() {}

    static final int THUMBNAIL_MAX_WIDTH = 1200, THUMBNAIL_MAX_HEIGHT = 800;

    private static final Pattern ALL_EMOJI = Pattern.compile("(\\p{IsEmoji_Presentation}|\\p{IsExtended_Pictographic}|\\x{FE0F})+");

    /// True for text made only of emoji (shown larger), false for empty text.
    public static boolean allEmoji(@Nullable String text) {
        return text != null && !text.isEmpty() && ALL_EMOJI.matcher(text).matches();
    }

    public static Safe of(View v, MessageView message) {
        return Safe.of(switch (message.content()) {
            case Attachment(AttachmentView attachment) -> attachment(v, attachment);
            case Sound sound -> sound(sound);
            case Text(String html) -> html;
            case Unrenderable u -> "";
        });
    }

    /// A play button, then the sound's image or text.
    static String sound(Sound sound) {
        String content = sound.imageSrc() != null
            ? "<img width=\"" + sound.imageWidth() + "\" height=\"" + sound.imageHeight() + "\" class=\"align--middle\" src=\"" + escape(sound.imageSrc()) + "\" />"
            : sound.text() != null ? escape(sound.text()) : "";
        return "<div class=\"sound\" data-controller=\"sound\" data-action=\"messages:play-&gt;sound#play\" data-sound-url-value=\"" + escape(sound.url())
            + "\"><button class=\"btn btn--plain\" data-action=\"sound#play\">🔊</button>" + content + "</div>";
    }

    public static String attachment(View v, AttachmentView a) {
        return switch (a.preview()) {
            case VIDEO -> constrained(dimensions(a), "<video src=\"" + escape(a.blobPath()) + "\" poster=\"" + escape(a.previewUrl())
                + "\" controls=\"controls\" preload=\"none\" width=\"100%\" height=\"100%\" class=\"message__attachment\"></video>");
            case IMAGE -> {
                Number[] d = dimensions(a);
                String size = d == null ? "" : " width=\"" + ruby(d[0]) + "\" height=\"" + ruby(d[1]) + "\"";
                String image = "<img" + size + " class=\"message__attachment\" loading=\"lazy\" src=\"" + escape(a.previewUrl()) + "\" />";
                yield constrained(d, "<a class=\"flex\" data-lightbox-target=\"image\" data-action=\"lightbox#open\" data-lightbox-url-value=\""
                    + escape(a.downloadPath()) + "\" href=\"" + escape(a.blobPath()) + "\">" + image + "</a>");
            }
            case FILE -> fileLink(v, a);
        };
    }

    private static String constrained(Number @Nullable [] d, String content) {
        if (d == null) return "<div class=\"max-inline-size center overflow-clip\">" + content + "</div>";
        Number half = d[0] instanceof Long || d[0] instanceof Integer ? (Number) Math.floorDiv(d[0].longValue(), 2) : (Number) (d[0].doubleValue() / 2);
        return "<div class=\"max-inline-size center flex overflow-clip\" style=\"width: " + ruby(half) + "px; aspect-ratio: "
            + ruby(d[0].doubleValue() / d[1].doubleValue()) + ";\">" + content + "</div>";
    }

    /// The analyzed size scaled down to fit the thumbnail bounds, or null when not analyzed.
    static Number @Nullable [] dimensions(AttachmentView a) {
        if (a.width() == null || a.height() == null) return null;
        double w = a.width().doubleValue(), h = a.height().doubleValue();
        if (w <= THUMBNAIL_MAX_WIDTH && h <= THUMBNAIL_MAX_HEIGHT) return new Number[] {a.width(), a.height()};
        double scale = Math.min(THUMBNAIL_MAX_WIDTH / w, THUMBNAIL_MAX_HEIGHT / h);
        return new Number[] {w * scale, h * scale};
    }

    /// File icon, name, download link and share button, with no whitespace between.
    static String fileLink(View v, AttachmentView a) {
        String filename = escape(a.filename());
        String download = escape(a.downloadPath());
        return "<div class=\"flex-inline align-center gap-half\">"
            + "<img class=\"colorize--black\" aria-hidden=\"true\" src=\"" + escape(v.asset("common-file-text.svg")) + "\" width=\"22\" height=\"22\" />"
            + "<span>" + filename + "</span>"
            + "<a class=\"btn message__action-btn hide-in-ios-pwa\" style=\"--width: auto;\" href=\"" + download + "\">"
            + "<img aria-hidden=\"true\" src=\"" + escape(v.asset("download.svg")) + "\" width=\"20\" height=\"20\" />"
            + "<span class=\"for-screen-reader\">Download " + filename + "</span></a>"
            + "<button class=\"btn message__action-btn\" style=\"--width: auto;\" data-controller=\"web-share\" data-action=\"web-share#share\" data-web-share-files-value=\"" + download + "\">"
            + "<img aria-hidden=\"true\" src=\"" + escape(v.asset("share.svg")) + "\" width=\"20\" height=\"20\" />"
            + "<span class=\"for-screen-reader\">Share " + filename + "</span></button>"
            + "</div>";
    }

    /// A number as Ruby prints it: integers bare; floats always with a fraction (`600.0`), in
    /// exponent form outside `1e-4...1e15` (`1.0e+15`).
    public static String ruby(Number n) {
        if (n instanceof Long || n instanceof Integer) return n.toString();
        double f = n.doubleValue();
        if (Double.isNaN(f)) return "NaN";
        if (Double.isInfinite(f)) return f > 0 ? "Infinity" : "-Infinity";
        if (f == 0) return (1 / f < 0) ? "-0.0" : "0.0";
        double abs = Math.abs(f);
        var decimal = new BigDecimal(Double.toString(abs)).stripTrailingZeros();
        String sign = f < 0 ? "-" : "";
        if (abs >= 1e-4 && abs < 1e15) {
            String plain = decimal.toPlainString();
            return sign + (plain.contains(".") ? plain : plain + ".0");
        }
        String digits = decimal.unscaledValue().toString();
        int exponent = digits.length() - 1 - decimal.scale();
        String mantissa = digits.length() == 1 ? digits + ".0" : digits.charAt(0) + "." + digits.substring(1);
        return sign + mantissa + "e" + (exponent < 0 ? "-" : "+") + String.format("%02d", Math.abs(exponent));
    }
}
