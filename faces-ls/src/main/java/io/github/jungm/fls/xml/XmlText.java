package io.github.jungm.fls.xml;

/**
 * A run of character data between markup. {@code parent} is null for text outside the root element.
 */
public record XmlText(int start, int end, XmlElement parent, boolean cdata) {
}
