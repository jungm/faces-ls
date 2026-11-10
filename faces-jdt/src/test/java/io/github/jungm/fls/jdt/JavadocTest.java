package io.github.jungm.fls.jdt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JavadocTest {
    @Test
    void stripsCommentMarkers() {
        assertEquals("ISO code of the currency used when none is given.",
                Javadoc.fromSource("/** ISO code of the currency used when none is given. */"));
        assertEquals("First line\nsecond line", Javadoc.fromSource("""
                /**
                 * First line
                 * second line
                 */"""));
    }

    @Test
    void convertsInlineTagsAndHtml() {
        assertEquals("Returns `null` for `java.util.List.get(int)`, see **bold** and _it_.\n\nNext",
                Javadoc.fromSource("/** Returns {@code null} for {@link java.util.List#get(int) get}, see <b>bold</b> and <i>it</i>.<p>Next */"));
        assertEquals("Items:\n- one\n- two", Javadoc.fromSource("/** Items:<ul><li>one</li><li>two</li></ul> */"));
    }

    @Test
    void marksBlockTags() {
        assertEquals("Sets it.\n\n**@param** value the value", Javadoc.fromSource("""
                /**
                 * Sets it.
                 * @param value the value
                 */"""));
    }
}
