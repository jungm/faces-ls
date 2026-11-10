package io.github.jungm.fls.el;

import java.util.ArrayList;
import java.util.List;

/**
 * One {@code ${...}} or {@code #{...}} occurrence. {@code start} is the offset of {@code $}/{@code #},
 * {@code exprStart}/{@code exprEnd} delimit the expression between the braces, and {@code end} is after the
 * closing brace (or equal to {@code exprEnd} when unterminated).
 */
public record ElRegion(int start, int exprStart, int exprEnd, int end, boolean deferred, boolean closed) {

    public boolean contains(int offset) {
        return offset >= exprStart && offset <= exprEnd;
    }

    /** Whether the text contains an EL expression ({@code #{...}} or {@code ${...}}). */
    public static boolean containsEl(String text) {
        return text.contains("#{") || text.contains("${");
    }

    /** The trimmed text when it is a non-blank literal without EL, else null (also for null). */
    public static String literal(String text) {
        if (text == null || text.isBlank() || containsEl(text)) {
            return null;
        }
        return text.trim();
    }

    /**
     * Finds EL regions in {@code text[from, to)}. Braces nest (map/set literals) and quoted strings may contain
     * braces. A backslash before {@code $}/{@code #} escapes the expression.
     */
    public static List<ElRegion> scan(String text, int from, int to) {
        List<ElRegion> regions = new ArrayList<>();
        int i = from;
        while (i < to - 1) {
            char c = text.charAt(i);
            if ((c == '$' || c == '#') && text.charAt(i + 1) == '{' && !(i > from && text.charAt(i - 1) == '\\')) {
                int exprStart = i + 2;
                int depth = 0;
                int j = exprStart;
                char quote = 0;
                boolean closed = false;
                while (j < to) {
                    char d = text.charAt(j);
                    if (quote != 0) {
                        if (d == '\\') {
                            j += 2;
                            continue;
                        }
                        if (d == quote) {
                            quote = 0;
                        }
                    } else if (d == '\'' || d == '"') {
                        quote = d;
                    } else if (d == '{') {
                        depth++;
                    } else if (d == '}') {
                        if (depth == 0) {
                            closed = true;
                            break;
                        }
                        depth--;
                    }
                    j++;
                }
                int exprEnd = Math.min(j, to);
                regions.add(new ElRegion(i, exprStart, exprEnd, closed ? exprEnd + 1 : exprEnd, c == '#', closed));
                i = closed ? exprEnd + 1 : exprEnd;
            } else {
                i++;
            }
        }
        return regions;
    }
}
