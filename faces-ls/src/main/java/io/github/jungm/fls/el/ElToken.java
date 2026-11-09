package io.github.jungm.fls.el;

/**
 * A lexical token; offsets are absolute document offsets.
 */
public record ElToken(Kind kind, String text, int start, int end) {

    public enum Kind {
        IDENTIFIER, STRING, NUMBER, OPERATOR, KEYWORD, EOF, ERROR
    }

    public boolean is(String value) {
        return (kind == Kind.OPERATOR || kind == Kind.KEYWORD) && text.equals(value);
    }
}
