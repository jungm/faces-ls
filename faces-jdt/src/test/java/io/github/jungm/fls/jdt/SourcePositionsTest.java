package io.github.jungm.fls.jdt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

class SourcePositionsTest {
    private static final String SOURCE = "class A {\n  int x;\r\n  void m() {}\n}";

    @Test
    void positionOfOffset() {
        assertPosition(0, 0, SourcePositions.position(SOURCE, 0));
        assertPosition(0, 6, SourcePositions.position(SOURCE, 6));
        assertPosition(1, 6, SourcePositions.position(SOURCE, SOURCE.indexOf("x;")));
        assertPosition(2, 7, SourcePositions.position(SOURCE, SOURCE.indexOf("m()")));
        // \r belongs to its line
        assertPosition(1, 9, SourcePositions.position(SOURCE, SOURCE.indexOf('\r') + 1));
    }

    @Test
    void offsetOfPosition() {
        assertEquals(SOURCE.indexOf("x;"), SourcePositions.offset(SOURCE, 1, 6));
        assertEquals(SOURCE.indexOf("m()"), SourcePositions.offset(SOURCE, 2, 7));
        assertEquals(SOURCE.indexOf('}', SOURCE.indexOf("m()") + 6), SourcePositions.offset(SOURCE, 3, 0));
    }

    @Test
    void roundTrip() {
        for (int offset = 0; offset <= SOURCE.length(); offset++) {
            JsonObject position = SourcePositions.position(SOURCE, offset);
            assertEquals(offset, SourcePositions.offset(SOURCE, position.get("line").getAsInt(),
                    position.get("character").getAsInt()));
        }
    }

    @Test
    void outOfRangeIsClamped() {
        assertPosition(3, 1, SourcePositions.position(SOURCE, 1000));
        assertPosition(0, 0, SourcePositions.position(null, 5));
        assertEquals(SOURCE.length(), SourcePositions.offset(SOURCE, 10, 0));
        assertEquals(SOURCE.length(), SourcePositions.offset(SOURCE, 3, 50));
    }

    private static void assertPosition(int line, int character, JsonObject position) {
        assertEquals(line, position.get("line").getAsInt(), "line of " + position);
        assertEquals(character, position.get("character").getAsInt(), "character of " + position);
    }
}
