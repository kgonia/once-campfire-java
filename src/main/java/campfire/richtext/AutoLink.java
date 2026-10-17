package campfire.richtext;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// rails_autolink's `auto_link` with `sanitize_options`, as `message_presentation` calls it: the
/// text is sanitized, then URLs and email addresses outside tags and anchors become links.
public final class AutoLink {
    private AutoLink() {}

    private static final Pattern AUTO_LINK_RE = Pattern.compile(
        "(?:(?i:((?:ed2k|ftp|http|https|irc|mailto|news|gopher|nntp|telnet|webcal|xmpp|callto|feed|svn|urn|aim|rsync|tag|ssh|sftp|rtsp|afs|file):))//"
            + "|(?i:www)\\.[a-zA-Z0-9_])[^ \\t\\r\\n\\x0B\\f<\\u00A0\"]+");
    private static final Pattern AUTO_EMAIL_RE = Pattern.compile(
        "\\A[a-zA-Z0-9_.!#$%+-]\\.?[a-zA-Z0-9_.!#$%&'*/=?^`{|}~+-]*@[a-zA-Z0-9_-]+(?:\\.[a-zA-Z0-9_-]+)+");
    private static final Pattern OPEN_ANCHOR_RE = Pattern.compile("(?i)\\A<a\\b.*?>");

    /// `\p{Word}` (Unicode `\w`): alphabetic, marks, decimal digits, connector punctuation and the
    /// joiners.
    static boolean isWord(char c) {
        if (Character.isAlphabetic(c) || c == 0x200c || c == 0x200d) return true;
        return switch (Character.getType(c)) {
            case Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK, Character.DECIMAL_DIGIT_NUMBER,
                 Character.CONNECTOR_PUNCTUATION -> true;
            default -> false;
        };
    }

    public static String autoLink(String text, Sanitizer.SafeList sanitizeOptions) {
        if (Html.isBlank(text)) return "";
        String sanitized = Sanitizer.sanitizeEscapingAttributeBrackets(text, sanitizeOptions);
        return autoLinkEmailAddresses(autoLinkUrls(sanitized));
    }

    /// Where tags and anchors are, to answer rails_autolink's `auto_linked?` without rescanning.
    private static final class TagIndex {
        final List<Integer> lts = new ArrayList<>();
        final List<Integer> gts = new ArrayList<>();
        Integer firstDanglingNewline;
        final List<int[]> openAnchors = new ArrayList<>();
        final List<Integer> closeAnchors = new ArrayList<>();

        TagIndex(String text) {
            Integer unclosedLt = null;
            for (int i = 0; i < text.length(); i++) {
                char b = text.charAt(i);
                if (b == '<') {
                    lts.add(i);
                    if (unclosedLt == null) unclosedLt = i;
                    if (text.regionMatches(true, i + 1, "/a>", 0, 3)) closeAnchors.add(i);
                    boolean afterPrevious = openAnchors.isEmpty() || openAnchors.getLast()[1] <= i;
                    if (afterPrevious) {
                        Matcher m = OPEN_ANCHOR_RE.matcher(text).region(i, text.length());
                        m.useAnchoringBounds(true);
                        if (m.lookingAt()) openAnchors.add(new int[] {i, m.end()});
                    }
                } else if (b == '>') {
                    gts.add(i);
                    unclosedLt = null;
                } else if (b == '\n' && firstDanglingNewline == null && unclosedLt != null && unclosedLt + 2 <= i) {
                    firstDanglingNewline = i;
                }
            }
        }

        boolean autoLinked(int start, int end) {
            return (openTagAtLineEnd(start) && closesTag(end)) || insideAnchor(start);
        }

        boolean openTagAtLineEnd(int start) {
            if (firstDanglingNewline != null && firstDanglingNewline < start) return true;
            int lastGtIndex = partitionPoint(gts, p -> p < start) - 1;
            Integer lastGt = lastGtIndex >= 0 ? gts.get(lastGtIndex) : null;
            int ltIndex = partitionPoint(lts, p -> lastGt != null && p <= lastGt);
            return ltIndex < lts.size() && lts.get(ltIndex) + 2 <= start;
        }

        boolean closesTag(int end) {
            return !gts.isEmpty() && gts.getLast() >= end;
        }

        boolean insideAnchor(int start) {
            int before = 0;
            while (before < openAnchors.size() && openAnchors.get(before)[1] <= start) before++;
            if (before == 0) return false;
            int anchorEnd = openAnchors.get(before - 1)[1];
            int closeIndex = partitionPoint(closeAnchors, p -> p < anchorEnd);
            return !(closeIndex < closeAnchors.size() && closeAnchors.get(closeIndex) + 4 <= start);
        }

        private static int partitionPoint(List<Integer> list, java.util.function.IntPredicate predicate) {
            int lo = 0, hi = list.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (predicate.test(list.get(mid))) lo = mid + 1;
                else hi = mid;
            }
            return lo;
        }
    }

    private static final String BRACKETS = "[](){}";

    private static String autoLinkUrls(String text) {
        var out = new StringBuilder(text.length() + 64);
        int last = 0;
        var tags = new TagIndex(text);
        Matcher m = AUTO_LINK_RE.matcher(text);
        while (m.find()) {
            out.append(text, last, m.start());
            last = m.end();
            String scheme = m.group(1);
            String href = m.group();
            if (tags.autoLinked(m.start(), m.end())) {
                out.append(href);
                continue;
            }
            var punctuation = new StringBuilder();
            int[] counts = new int[6];
            for (int i = 0; i < href.length(); i++) {
                int b = BRACKETS.indexOf(href.charAt(i));
                if (b >= 0) counts[b]++;
            }
            while (!href.isEmpty()) {
                char c = href.charAt(href.length() - 1);
                if (isWord(c) || c == '/' || c == '-' || c == '=' || c == ';') break;
                href = href.substring(0, href.length() - 1);
                punctuation.append(c);
                int b = BRACKETS.indexOf(c);
                if (b >= 0) counts[b]--;
                int closing = "])}".indexOf(c);
                if (closing >= 0) {
                    int opening = BRACKETS.indexOf("[({".charAt(closing));
                    if (counts[opening] > counts[b]) {
                        href = href + c;
                        punctuation.setLength(punctuation.length() - 1);
                        break;
                    }
                }
            }
            String trailingGt = "";
            if (href.endsWith("&gt;")) {
                href = href.substring(0, href.length() - 4);
                trailingGt = "&gt;";
            }
            String linkText = href;
            if (scheme == null) href = "http://" + href;
            linkText = Sanitizer.sanitize(linkText, Sanitizer.DEFAULTS);
            href = Sanitizer.sanitize(href, Sanitizer.DEFAULTS);
            out.append("<a target=\"_blank\" href=\"").append(href.replace("\"", "&quot;")).append("\">").append(linkText).append("</a>");
            out.append(Html.escape(punctuation.reverse().toString()));
            out.append(trailingGt);
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    private static boolean isEmailLocalChar(char c) {
        return (c < 128 && Character.isLetterOrDigit(c)) || "_.!#$%&'*/=?^`{|}~+-".indexOf(c) >= 0;
    }

    private static String autoLinkEmailAddresses(String text) {
        if (text.indexOf('@') < 0) return text;
        var out = new StringBuilder(text.length() + 32);
        int copied = 0;
        int position = 0;
        var tags = new TagIndex(text);
        Matcher matcher = AUTO_EMAIL_RE.matcher(text);
        matcher.useAnchoringBounds(true);
        while (position < text.length()) {
            boolean precededByLocalChar = position > 0 && isEmailLocalChar(text.charAt(position - 1));
            boolean found = false;
            if (!precededByLocalChar) {
                matcher.region(position, text.length());
                found = matcher.lookingAt();
            }
            if (!found) {
                position++;
                continue;
            }
            int start = matcher.start(), end = matcher.end();
            String email = text.substring(start, end);
            out.append(text, copied, start);
            if (tags.autoLinked(start, end)) {
                out.append(email);
            } else {
                String sanitized = Sanitizer.sanitize(email, Sanitizer.DEFAULTS);
                String display = sanitized.equals(email) ? Html.escape(email) : sanitized;
                String href = "mailto:" + urlEncode(sanitized).replace("%40", "@");
                out.append("<a target=\"_blank\" href=\"").append(Html.escape(href)).append("\">").append(display).append("</a>");
            }
            copied = end;
            position = Math.max(end, position + 1);
        }
        out.append(text, copied, text.length());
        return out.toString();
    }

    /// `ERB::Util.url_encode`
    public static String urlEncode(String s) {
        // URLEncoder keeps `*` and makes space `+`; ERB escapes both and keeps `~`.
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }
}
