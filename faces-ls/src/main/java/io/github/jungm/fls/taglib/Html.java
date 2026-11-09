package io.github.jungm.fls.taglib;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the HTML found in taglib descriptions into compact Markdown for hover and completion docs.
 */
public final class Html {
    private static final Pattern ANCHOR = Pattern.compile("(?is)<a\\b[^>]*>(.*?)</a>");
    private static final Pattern WHITESPACE = Pattern.compile("[ \\t\\x0B\\f\\r]+");

    private Html() {
    }

    public static String toMarkdown(String html) {
        if (html == null) {
            return null;
        }
        String text = html.replace("\r", "");
        Matcher anchors = ANCHOR.matcher(text);
        text = anchors.replaceAll(m -> Matcher.quoteReplacement(m.group(1)));
        text = text
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</?p\\b[^>]*>", "\n\n")
                .replaceAll("(?i)</?div\\b[^>]*>", "\n\n")
                .replaceAll("(?i)<li\\b[^>]*>", "\n- ")
                .replaceAll("(?i)</?(ul|ol)\\b[^>]*>", "\n")
                .replaceAll("(?i)</?(code|tt)\\b[^>]*>", "`")
                .replaceAll("(?i)</?(strong|b)\\b[^>]*>", "**")
                .replaceAll("(?i)</?(em|i)\\b[^>]*>", "_")
                .replaceAll("(?i)<h\\d\\b[^>]*>", "\n\n**")
                .replaceAll("(?i)</h\\d>", "**\n\n")
                .replaceAll("<[^>]+>", "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&nbsp;", " ")
                .replace("&amp;", "&");
        StringBuilder result = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            result.append(WHITESPACE.matcher(line.strip()).replaceAll(" ")).append('\n');
        }
        String collapsed = result.toString().replaceAll("\n{3,}", "\n\n").strip();
        // join hard-wrapped lines inside paragraphs
        collapsed = collapsed.replaceAll("(?<=[^\\n])\\n(?=[^\\n\\-])", " ");
        return collapsed.isEmpty() ? null : collapsed;
    }
}
