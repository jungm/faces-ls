package io.github.jungm.fls.el;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tokenizes an EL expression. XML entity spellings of operators ({@code &lt;}, {@code &amp;&amp;} ...) are
 * normalized to their operator text, since the lexer runs on raw document text.
 */
public final class ElLexer {
    static final Set<String> KEYWORDS = Set.of("and", "or", "not", "eq", "ne", "lt", "gt", "le", "ge", "div", "mod",
            "empty", "instanceof", "true", "false", "null");

    private static final Map<String, String> ENTITY_OPERATORS = Map.of(
            "&amp;&amp;", "&&", "&lt;=", "<=", "&gt;=", ">=", "&lt;", "<", "&gt;", ">");

    private static final String[] OPERATORS = {
            "->", "+=", "==", "!=", "<=", ">=", "&&", "||", "+", "-", "*", "/", "%", "<", ">", "!", "?", ":", "=",
            ".", ",", ";", "(", ")", "[", "]", "{", "}"};

    private ElLexer() {
    }

    public static List<ElToken> tokenize(String text, int start, int end) {
        List<ElToken> tokens = new ArrayList<>();
        int i = start;
        while (i < end) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int j = i + 1;
                while (j < end && Character.isJavaIdentifierPart(text.charAt(j))) {
                    j++;
                }
                String word = text.substring(i, j);
                tokens.add(new ElToken(KEYWORDS.contains(word) ? ElToken.Kind.KEYWORD : ElToken.Kind.IDENTIFIER, word, i, j));
                i = j;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < end && Character.isDigit(text.charAt(i + 1)))) {
                int j = i;
                while (j < end && (Character.isDigit(text.charAt(j)) || text.charAt(j) == '.')) {
                    j++;
                }
                if (j < end && (text.charAt(j) == 'e' || text.charAt(j) == 'E')) {
                    j++;
                    if (j < end && (text.charAt(j) == '+' || text.charAt(j) == '-')) {
                        j++;
                    }
                    while (j < end && Character.isDigit(text.charAt(j))) {
                        j++;
                    }
                }
                tokens.add(new ElToken(ElToken.Kind.NUMBER, text.substring(i, j), i, j));
                i = j;
                continue;
            }
            if (c == '\'' || c == '"') {
                StringBuilder value = new StringBuilder();
                int j = i + 1;
                boolean closed = false;
                while (j < end) {
                    char d = text.charAt(j);
                    if (d == '\\' && j + 1 < end) {
                        value.append(text.charAt(j + 1));
                        j += 2;
                        continue;
                    }
                    if (d == c) {
                        closed = true;
                        j++;
                        break;
                    }
                    value.append(d);
                    j++;
                }
                tokens.add(new ElToken(closed ? ElToken.Kind.STRING : ElToken.Kind.ERROR, value.toString(), i, j));
                i = j;
                continue;
            }
            if (c == '&') {
                String matched = null;
                for (Map.Entry<String, String> entity : ENTITY_OPERATORS.entrySet()) {
                    if (text.startsWith(entity.getKey(), i) && i + entity.getKey().length() <= end
                            && (matched == null || entity.getKey().length() > matched.length())) {
                        matched = entity.getKey();
                    }
                }
                if (matched != null) {
                    tokens.add(new ElToken(ElToken.Kind.OPERATOR, ENTITY_OPERATORS.get(matched), i, i + matched.length()));
                    i += matched.length();
                    continue;
                }
            }
            String operator = null;
            for (String candidate : OPERATORS) {
                if (text.startsWith(candidate, i) && i + candidate.length() <= end) {
                    operator = candidate;
                    break;
                }
            }
            if (operator != null) {
                tokens.add(new ElToken(ElToken.Kind.OPERATOR, operator, i, i + operator.length()));
                i += operator.length();
            } else {
                tokens.add(new ElToken(ElToken.Kind.ERROR, String.valueOf(c), i, i + 1));
                i++;
            }
        }
        tokens.add(new ElToken(ElToken.Kind.EOF, "", end, end));
        return tokens;
    }
}
