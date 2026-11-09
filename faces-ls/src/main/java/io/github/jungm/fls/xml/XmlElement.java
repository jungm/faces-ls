package io.github.jungm.fls.xml;

import java.util.ArrayList;
import java.util.List;

/**
 * An element with the offsets of its tag parts. {@code end} is the offset after the closing {@code >} of the end
 * tag (or of the start tag for empty elements), or the end of the enclosing content when the element is never
 * closed.
 */
public final class XmlElement {
    final String name;
    final int start;
    final int nameStart;
    final int nameEnd;
    int startTagEnd = -1;
    boolean selfClosing;
    int closeTagStart = -1;
    int closeNameStart = -1;
    int closeNameEnd = -1;
    int end = -1;
    final List<XmlAttribute> attributes = new ArrayList<>();
    final List<XmlElement> children = new ArrayList<>();
    XmlElement parent;

    XmlElement(String name, int start, int nameStart, int nameEnd) {
        this.name = name;
        this.start = start;
        this.nameStart = nameStart;
        this.nameEnd = nameEnd;
    }

    public String name() {
        return name;
    }

    public String prefix() {
        int colon = name.indexOf(':');
        return colon < 0 ? "" : name.substring(0, colon);
    }

    public String localName() {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    public int start() {
        return start;
    }

    public int nameStart() {
        return nameStart;
    }

    public int nameEnd() {
        return nameEnd;
    }

    /** Offset after the {@code >} of the start tag, or -1 when the start tag is unterminated. */
    public int startTagEnd() {
        return startTagEnd;
    }

    public boolean selfClosing() {
        return selfClosing;
    }

    public int closeTagStart() {
        return closeTagStart;
    }

    public int closeNameStart() {
        return closeNameStart;
    }

    public int closeNameEnd() {
        return closeNameEnd;
    }

    public int end() {
        return end;
    }

    public List<XmlAttribute> attributes() {
        return attributes;
    }

    public List<XmlElement> children() {
        return children;
    }

    public XmlElement parent() {
        return parent;
    }

    public XmlAttribute attribute(String attributeName) {
        for (XmlAttribute attribute : attributes) {
            if (attribute.name().equals(attributeName)) {
                return attribute;
            }
        }
        return null;
    }

    public String attributeValue(String attributeName) {
        XmlAttribute attribute = attribute(attributeName);
        return attribute == null ? null : attribute.value();
    }

    /** True when the offset is inside the start tag (between the name and the closing {@code >}). */
    public boolean inStartTag(int offset) {
        int tagEnd = startTagEnd < 0 ? Integer.MAX_VALUE : startTagEnd - (selfClosing ? 2 : 1);
        return offset > nameEnd && offset <= tagEnd;
    }

    /** Resolves a namespace prefix ("" for the default namespace) through this element and its ancestors. */
    public String namespaceFor(String namespacePrefix) {
        String attributeName = namespacePrefix.isEmpty() ? "xmlns" : "xmlns:" + namespacePrefix;
        for (XmlElement element = this; element != null; element = element.parent) {
            String value = element.attributeValue(attributeName);
            if (value != null) {
                return value.trim();
            }
        }
        return null;
    }

    public String namespace() {
        return namespaceFor(prefix());
    }

    /** Text content directly inside this element, concatenated. */
    public String contentText(String documentText) {
        if (startTagEnd < 0 || selfClosing) {
            return "";
        }
        int contentEnd = closeTagStart >= 0 ? closeTagStart : end;
        if (contentEnd < startTagEnd) {
            return "";
        }
        return documentText.substring(startTagEnd, Math.min(contentEnd, documentText.length()));
    }

    @Override
    public String toString() {
        return "<" + name + ">@" + start;
    }
}
