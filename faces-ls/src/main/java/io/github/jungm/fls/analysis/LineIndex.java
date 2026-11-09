package io.github.jungm.fls.analysis;

import java.util.Arrays;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Converts between character offsets and LSP positions (UTF-16 code units, which match Java chars).
 */
public final class LineIndex {
    private final int[] lineStarts;
    private final int length;

    public LineIndex(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        lineStarts = new int[lines];
        int line = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lineStarts[line++] = i + 1;
            }
        }
        length = text.length();
    }

    public Position position(int offset) {
        int clamped = Math.max(0, Math.min(offset, length));
        int line = Arrays.binarySearch(lineStarts, clamped);
        if (line < 0) {
            line = -line - 2;
        }
        return new Position(line, clamped - lineStarts[line]);
    }

    public Range range(int start, int end) {
        return new Range(position(start), position(end));
    }

    public int offset(Position position) {
        if (position.getLine() >= lineStarts.length) {
            return length;
        }
        int lineStart = lineStarts[position.getLine()];
        int lineEnd = position.getLine() + 1 < lineStarts.length ? lineStarts[position.getLine() + 1] - 1 : length;
        return Math.min(lineStart + position.getCharacter(), lineEnd);
    }

    /** Offset of the start of a line, for locations given as line numbers. */
    public int lineStart(int line) {
        return line < lineStarts.length ? lineStarts[line] : length;
    }
}
