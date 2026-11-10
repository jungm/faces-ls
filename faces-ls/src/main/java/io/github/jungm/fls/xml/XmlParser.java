package io.github.jungm.fls.xml;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Hand-written, fault-tolerant XML scanner. It never fails: unterminated tags, unbalanced end tags and stray
 * characters produce a best-effort tree, which is what editing features need while the user types.
 */
final class XmlParser {
    private final String text;
    private final int length;
    private final XmlDocument document;
    private final Deque<XmlElement> open = new ArrayDeque<>();
    private int pos;

    XmlParser(String text) {
        this.text = text;
        this.length = text.length();
        this.document = new XmlDocument(text);
    }

    XmlDocument parse() {
        int textStart = 0;
        while (pos < length) {
            char c = text.charAt(pos);
            if (c != '<') {
                pos++;
                continue;
            }
            addText(textStart, pos);
            if (text.startsWith("<!--", pos)) {
                int close = text.indexOf("-->", pos + 4);
                int end = close < 0 ? length : close + 3;
                document.comments().add(new int[] {pos, end});
                pos = end;
            } else if (text.startsWith("<![CDATA[", pos)) {
                int close = text.indexOf("]]>", pos + 9);
                int contentEnd = close < 0 ? length : close;
                document.texts().add(new XmlText(pos + 9, contentEnd, open.peek(), true));
                pos = close < 0 ? length : close + 3;
            } else if (text.startsWith("<?", pos)) {
                int close = text.indexOf("?>", pos + 2);
                pos = close < 0 ? length : close + 2;
            } else if (text.startsWith("<!", pos)) {
                pos = skipDeclaration(pos + 2);
            } else if (text.startsWith("</", pos)) {
                parseEndTag();
            } else {
                parseStartTag();
            }
            textStart = pos;
        }
        addText(textStart, length);
        while (!open.isEmpty()) {
            XmlElement element = open.pop();
            element.end = length;
        }
        return document;
    }

    private void addText(int start, int end) {
        if (end > start) {
            document.texts().add(new XmlText(start, end, open.peek(), false));
        }
    }

    private int skipDeclaration(int from) {
        int depth = 0;
        for (int i = from; i < length; i++) {
            char c = text.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == '>' && depth <= 0) {
                return i + 1;
            }
        }
        return length;
    }

    private void parseStartTag() {
        int start = pos;
        int nameStart = pos + 1;
        int i = nameStart;
        while (i < length && XmlDocument.isNameChar(text.charAt(i))) {
            i++;
        }
        XmlElement element = new XmlElement(text.substring(nameStart, i), start, nameStart, i);
        XmlElement parent = open.peek();
        element.parent = parent;
        if (parent != null) {
            parent.children.add(element);
        } else {
            document.roots().add(element);
        }
        pos = i;
        parseAttributes(element);
        if (element.startTagEnd < 0) {
            // unterminated start tag: treat it as empty so the rest of the document keeps its structure
            element.end = pos;
            return;
        }
        if (element.selfClosing) {
            element.end = element.startTagEnd;
        } else {
            open.push(element);
        }
    }

    private void parseAttributes(XmlElement element) {
        while (pos < length) {
            char c = text.charAt(pos);
            if (c == '>') {
                pos++;
                element.startTagEnd = pos;
                return;
            }
            if (c == '/' && pos + 1 < length && text.charAt(pos + 1) == '>') {
                pos += 2;
                element.startTagEnd = pos;
                element.selfClosing = true;
                return;
            }
            if (c == '<') {
                // a new tag starts before this one was closed
                return;
            }
            if (Character.isWhitespace(c) || !XmlDocument.isNameChar(c)) {
                pos++;
                continue;
            }
            int nameStart = pos;
            while (pos < length && XmlDocument.isNameChar(text.charAt(pos))) {
                pos++;
            }
            int nameEnd = pos;
            String name = text.substring(nameStart, nameEnd);
            int lookahead = pos;
            while (lookahead < length && Character.isWhitespace(text.charAt(lookahead))) {
                lookahead++;
            }
            if (lookahead < length && text.charAt(lookahead) == '=') {
                lookahead++;
                while (lookahead < length && Character.isWhitespace(text.charAt(lookahead))) {
                    lookahead++;
                }
                if (lookahead < length && (text.charAt(lookahead) == '"' || text.charAt(lookahead) == '\'')) {
                    char quote = text.charAt(lookahead);
                    int valueStart = lookahead + 1;
                    int valueEnd = valueStart;
                    while (valueEnd < length && text.charAt(valueEnd) != quote) {
                        // an unterminated value ends at the line end once a tag delimiter shows up
                        if (text.charAt(valueEnd) == '\n' && looksLikeTagFollows(valueEnd)) {
                            break;
                        }
                        valueEnd++;
                    }
                    String value = text.substring(valueStart, valueEnd);
                    element.attributes.add(new XmlAttribute(name, nameStart, nameEnd, valueStart, valueEnd, value, element));
                    pos = valueEnd < length && text.charAt(valueEnd) == quote ? valueEnd + 1 : valueEnd;
                } else {
                    element.attributes.add(new XmlAttribute(name, nameStart, nameEnd, -1, -1, null, element));
                    pos = lookahead;
                }
            } else {
                element.attributes.add(new XmlAttribute(name, nameStart, nameEnd, -1, -1, null, element));
            }
        }
    }

    private boolean looksLikeTagFollows(int newline) {
        int i = newline + 1;
        while (i < length && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i < length && text.charAt(i) == '<';
    }

    private void parseEndTag() {
        int start = pos;
        int nameStart = pos + 2;
        int i = nameStart;
        while (i < length && XmlDocument.isNameChar(text.charAt(i))) {
            i++;
        }
        String name = text.substring(nameStart, i);
        int close = i;
        while (close < length && text.charAt(close) != '>' && text.charAt(close) != '<') {
            close++;
        }
        int end = close < length && text.charAt(close) == '>' ? close + 1 : close;
        pos = end;

        boolean matches = open.stream().anyMatch(e -> e.name.equals(name));
        if (!matches) {
            return;
        }
        while (!open.isEmpty()) {
            XmlElement element = open.pop();
            if (element.name.equals(name)) {
                element.closeTagStart = start;
                element.closeNameStart = nameStart;
                element.closeNameEnd = i;
                element.end = end;
                return;
            }
            element.end = start;
        }
    }
}
