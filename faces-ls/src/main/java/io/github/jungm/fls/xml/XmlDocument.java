package io.github.jungm.fls.xml;

import java.util.ArrayList;
import java.util.List;

/**
 * A fault-tolerant parse of an XML document. Offsets are character offsets into the original text.
 */
public final class XmlDocument {
    private final String text;
    private final List<XmlElement> roots = new ArrayList<>();
    private final List<int[]> comments = new ArrayList<>();
    private final List<XmlText> texts = new ArrayList<>();

    XmlDocument(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    public List<XmlElement> roots() {
        return roots;
    }

    public XmlElement root() {
        return roots.isEmpty() ? null : roots.get(0);
    }

    /** Text content segments (outside tags, comments and CDATA markers). */
    public List<XmlText> texts() {
        return texts;
    }

    List<int[]> comments() {
        return comments;
    }

    public boolean inComment(int offset) {
        for (int[] comment : comments) {
            if (offset > comment[0] && offset < comment[1]) {
                return true;
            }
        }
        return false;
    }

    /** The innermost element whose range contains the offset. */
    public XmlElement elementAt(int offset) {
        XmlElement found = null;
        List<XmlElement> candidates = roots;
        outer:
        while (true) {
            for (XmlElement element : candidates) {
                if (offset >= element.start() && (offset <= element.end() || element.end() < 0)) {
                    found = element;
                    candidates = element.children();
                    continue outer;
                }
            }
            return found;
        }
    }

    public List<XmlElement> allElements() {
        List<XmlElement> result = new ArrayList<>();
        for (XmlElement root : roots) {
            collect(root, result);
        }
        return result;
    }

    private static void collect(XmlElement element, List<XmlElement> result) {
        result.add(element);
        for (XmlElement child : element.children()) {
            collect(child, result);
        }
    }

    public static XmlDocument parse(String text) {
        return new XmlParser(text).parse();
    }
}
