package io.github.jungm.fls.xml;

/**
 * An attribute. {@code valueStart}/{@code valueEnd} delimit the value without its quotes; both are -1 when the
 * attribute has no value yet.
 */
public record XmlAttribute(String name, int nameStart, int nameEnd, int valueStart, int valueEnd, String value,
                           XmlElement element) {

    public boolean hasValue() {
        return valueStart >= 0;
    }

    public String prefix() {
        int colon = name.indexOf(':');
        return colon < 0 ? "" : name.substring(0, colon);
    }

    public String localName() {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    /** True when the offset is inside the value (between the quotes, inclusive of the end). */
    public boolean valueContains(int offset) {
        return valueStart >= 0 && offset >= valueStart && offset <= valueEnd;
    }
}
