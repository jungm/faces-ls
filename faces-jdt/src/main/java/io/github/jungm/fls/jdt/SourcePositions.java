package io.github.jungm.fls.jdt;

import com.google.gson.JsonObject;

/**
 * Conversion between source offsets and LSP positions (zero-based line, UTF-16 character in that line). Lines end at
 * {@code \n}; a {@code \r} before it counts as a character of the line.
 */
final class SourcePositions {
    private SourcePositions() {
    }

    /** {@code {line, character}} of an offset; offsets beyond the source are clamped to its end. */
    static JsonObject position(String source, int offset) {
        int line = 0;
        int lineStart = 0;
        int limit = Math.max(0, Math.min(offset, source == null ? 0 : source.length()));
        for (int i = 0; i < limit; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        JsonObject position = new JsonObject();
        position.addProperty("line", line);
        position.addProperty("character", limit - lineStart);
        return position;
    }

    /** The offset of a position; positions beyond the source are clamped to its end. */
    static int offset(String source, int line, int character) {
        int offset = 0;
        for (int current = 0; current < line && offset < source.length(); offset++) {
            if (source.charAt(offset) == '\n') {
                current++;
            }
        }
        return Math.min(offset + character, source.length());
    }
}
