package io.github.jungm.fls.features;

import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.xml.XmlAttribute;
import io.github.jungm.fls.xml.XmlElement;

/**
 * What the cursor is on, from the markup point of view.
 *
 * @param replaceStart start of the word being typed (tag name, attribute name or value prefix)
 */
public record Cursor(Kind kind, XmlElement element, XmlAttribute attribute, int replaceStart, String prefix) {

    public enum Kind {
        TAG_NAME, CLOSE_TAG_NAME, ATTRIBUTE_NAME, ATTRIBUTE_VALUE, TEXT, NONE
    }

    static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == ':' || c == '_' || c == '-' || c == '.';
    }

    public static Cursor at(FacesDocument document, int offset) {
        String text = document.text();
        if (document.xml().inComment(offset)) {
            return new Cursor(Kind.NONE, null, null, offset, "");
        }
        int wordStart = offset;
        while (wordStart > 0 && isNameChar(text.charAt(wordStart - 1))) {
            wordStart--;
        }
        String word = text.substring(wordStart, offset);
        XmlElement element = document.xml().elementAt(offset);
        if (element != null) {
            for (XmlAttribute attribute : element.attributes()) {
                if (attribute.valueContains(offset)) {
                    int valueStart = attribute.valueStart();
                    return new Cursor(Kind.ATTRIBUTE_VALUE, element, attribute, valueStart, text.substring(valueStart, offset));
                }
            }
        }
        if (wordStart > 0 && text.charAt(wordStart - 1) == '<') {
            return new Cursor(Kind.TAG_NAME, element, null, wordStart, word);
        }
        if (wordStart > 1 && text.charAt(wordStart - 1) == '/' && text.charAt(wordStart - 2) == '<') {
            return new Cursor(Kind.CLOSE_TAG_NAME, element, null, wordStart, word);
        }
        if (element != null && element.inStartTag(offset)
                && (wordStart == 0 || Character.isWhitespace(text.charAt(wordStart - 1)))) {
            return new Cursor(Kind.ATTRIBUTE_NAME, element, null, wordStart, word);
        }
        return new Cursor(Kind.TEXT, element, null, offset, "");
    }

    /** The markup item under the offset, for hover and definition (not for completion). */
    public static Cursor on(FacesDocument document, int offset) {
        XmlElement element = document.xml().elementAt(offset);
        if (element == null) {
            return new Cursor(Kind.NONE, null, null, offset, "");
        }
        if (offset >= element.nameStart() && offset <= element.nameEnd()) {
            return new Cursor(Kind.TAG_NAME, element, null, element.nameStart(), element.name());
        }
        if (element.closeNameStart() >= 0 && offset >= element.closeNameStart() && offset <= element.closeNameEnd()) {
            return new Cursor(Kind.CLOSE_TAG_NAME, element, null, element.closeNameStart(), element.name());
        }
        for (XmlAttribute attribute : element.attributes()) {
            if (offset >= attribute.nameStart() && offset <= attribute.nameEnd()) {
                return new Cursor(Kind.ATTRIBUTE_NAME, element, attribute, attribute.nameStart(), attribute.name());
            }
            if (attribute.valueContains(offset)) {
                return new Cursor(Kind.ATTRIBUTE_VALUE, element, attribute, attribute.valueStart(), attribute.value());
            }
        }
        return new Cursor(Kind.TEXT, element, null, offset, "");
    }
}
