package io.github.jungm.fls.el;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent EL parser that recovers from errors: incomplete input such as {@code bean.} or
 * {@code a +} still yields a tree (with {@link ElNode.Member} names or {@link ElNode.Missing} nodes), so completion
 * and hover keep working while the user types.
 */
public final class ElParser {

    public record Problem(String message, int start, int end) {
    }

    public record Result(ElNode root, List<Problem> problems) {
    }

    private final List<ElToken> tokens;
    private final List<Problem> problems = new ArrayList<>();
    private final int end;
    private int index;

    private ElParser(List<ElToken> tokens, int end) {
        this.tokens = tokens;
        this.end = end;
    }

    public static Result parse(String text, int start, int end) {
        ElParser parser = new ElParser(ElLexer.tokenize(text, start, end), end);
        if (parser.peek().kind() == ElToken.Kind.EOF) {
            return new Result(new ElNode.Missing(start, end), parser.problems);
        }
        ElNode root = parser.sequence();
        while (parser.peek().kind() != ElToken.Kind.EOF) {
            ElToken unexpected = parser.next();
            parser.problems.add(new Problem("Unexpected '" + unexpected.text() + "'", unexpected.start(), unexpected.end()));
        }
        return new Result(root, parser.problems);
    }

    public static Result parse(String text, ElRegion region) {
        return parse(text, region.exprStart(), region.exprEnd());
    }

    private ElToken peek() {
        return tokens.get(index);
    }

    private ElToken peek(int ahead) {
        return tokens.get(Math.min(index + ahead, tokens.size() - 1));
    }

    private ElToken next() {
        ElToken token = tokens.get(index);
        if (index < tokens.size() - 1) {
            index++;
        }
        return token;
    }

    private boolean accept(String value) {
        if (peek().is(value)) {
            next();
            return true;
        }
        return false;
    }

    private int lastEnd() {
        return index == 0 ? peek().start() : tokens.get(index - 1).end();
    }

    private ElNode missing() {
        ElToken token = peek();
        problems.add(new Problem(token.kind() == ElToken.Kind.EOF ? "Expression expected" : "Unexpected '" + token.text() + "'",
                token.start(), Math.max(token.end(), token.start())));
        return new ElNode.Missing(token.start(), token.start());
    }

    private void expect(String value) {
        if (!accept(value)) {
            ElToken token = peek();
            problems.add(new Problem("'" + value + "' expected", token.start(), token.end()));
        }
    }

    private ElNode sequence() {
        ElNode first = assignment();
        if (!peek().is(";")) {
            return first;
        }
        List<ElNode> expressions = new ArrayList<>();
        expressions.add(first);
        while (accept(";")) {
            expressions.add(assignment());
        }
        return new ElNode.Sequence(expressions, first.start(), lastEnd());
    }

    private ElNode assignment() {
        if (isLambdaStart()) {
            return lambda();
        }
        ElNode target = ternary();
        if (peek().is("=")) {
            next();
            ElNode value = assignment();
            return new ElNode.Assignment(target, value, target.start(), lastEnd());
        }
        return target;
    }

    private boolean isLambdaStart() {
        ElToken token = peek();
        if (token.kind() == ElToken.Kind.IDENTIFIER && peek(1).is("->")) {
            return true;
        }
        if (!token.is("(")) {
            return false;
        }
        int i = 1;
        if (peek(i).is(")")) {
            return peek(i + 1).is("->");
        }
        while (true) {
            if (peek(i).kind() != ElToken.Kind.IDENTIFIER) {
                return false;
            }
            i++;
            if (peek(i).is(")")) {
                return peek(i + 1).is("->");
            }
            if (!peek(i).is(",")) {
                return false;
            }
            i++;
        }
    }

    private ElNode lambda() {
        int start = peek().start();
        List<ElNode.Identifier> parameters = new ArrayList<>();
        if (peek().kind() == ElToken.Kind.IDENTIFIER) {
            ElToken name = next();
            parameters.add(new ElNode.Identifier(name.text(), name.start(), name.end()));
        } else {
            expect("(");
            while (peek().kind() == ElToken.Kind.IDENTIFIER) {
                ElToken name = next();
                parameters.add(new ElNode.Identifier(name.text(), name.start(), name.end()));
                if (!accept(",")) {
                    break;
                }
            }
            expect(")");
        }
        expect("->");
        ElNode body = assignment();
        return new ElNode.Lambda(parameters, body, start, lastEnd());
    }

    private ElNode ternary() {
        ElNode condition = binary(0);
        if (!peek().is("?")) {
            return condition;
        }
        next();
        ElNode consequence = assignment();
        expect(":");
        ElNode alternative = assignment();
        return new ElNode.Ternary(condition, consequence, alternative, condition.start(), lastEnd());
    }

    private static final String[][] LEVELS = {
            {"||", "or"},
            {"&&", "and"},
            {"==", "!=", "eq", "ne"},
            {"<", ">", "<=", ">=", "lt", "gt", "le", "ge", "instanceof"},
            {"+="},
            {"+", "-"},
            {"*", "/", "%", "div", "mod"},
    };

    private ElNode binary(int level) {
        if (level == LEVELS.length) {
            return unary();
        }
        ElNode left = binary(level + 1);
        while (true) {
            String operator = null;
            for (String candidate : LEVELS[level]) {
                if (peek().is(candidate)) {
                    operator = candidate;
                    break;
                }
            }
            if (operator == null) {
                return left;
            }
            next();
            ElNode right = binary(level + 1);
            left = new ElNode.Binary(operator, left, right, left.start(), lastEnd());
        }
    }

    private ElNode unary() {
        ElToken token = peek();
        if (token.is("-") || token.is("!") || token.is("not") || token.is("empty")) {
            next();
            ElNode operand = unary();
            return new ElNode.Unary(token.text(), operand, token.start(), lastEnd());
        }
        return postfix(primary());
    }

    private ElNode postfix(ElNode node) {
        while (true) {
            if (peek().is(".")) {
                ElToken dot = next();
                if (peek().kind() == ElToken.Kind.IDENTIFIER || peek().kind() == ElToken.Kind.KEYWORD) {
                    ElToken name = next();
                    node = new ElNode.Member(node, name.text(), name.start(), name.end(), node.start(), name.end());
                } else {
                    problems.add(new Problem("Property name expected", dot.end(), dot.end()));
                    node = new ElNode.Member(node, "", dot.end(), dot.end(), node.start(), dot.end());
                    return node;
                }
            } else if (peek().is("[")) {
                next();
                ElNode indexNode = peek().is("]") ? missing() : sequence();
                expect("]");
                node = new ElNode.Index(node, indexNode, node.start(), lastEnd());
            } else if (peek().is("(")) {
                List<ElNode> arguments = arguments();
                node = new ElNode.Call(node, arguments, node.start(), lastEnd());
            } else {
                return node;
            }
        }
    }

    private List<ElNode> arguments() {
        expect("(");
        List<ElNode> arguments = new ArrayList<>();
        if (accept(")")) {
            return arguments;
        }
        while (true) {
            arguments.add(assignment());
            if (!accept(",")) {
                break;
            }
        }
        expect(")");
        return arguments;
    }

    private ElNode primary() {
        ElToken token = peek();
        switch (token.kind()) {
            case IDENTIFIER -> {
                next();
                if (peek().is(":") && peek(1).kind() == ElToken.Kind.IDENTIFIER && peek(2).is("(")
                        && peek().start() == token.end()) {
                    next();
                    ElToken name = next();
                    List<ElNode> arguments = arguments();
                    return new ElNode.Function(token.text(), name.text(), token.start(), name.start(), name.end(),
                            arguments, token.start(), lastEnd());
                }
                if (peek().is(":") && peek().start() == token.end() && peek(1).kind() == ElToken.Kind.EOF) {
                    // "fn:" typed so far: keep the prefix for function completion
                    ElToken colon = next();
                    return new ElNode.Function(token.text(), "", token.start(), colon.end(), colon.end(), List.of(),
                            token.start(), colon.end());
                }
                if (peek().is(":") && peek().start() == token.end() && peek(1).kind() == ElToken.Kind.IDENTIFIER
                        && peek(2).kind() == ElToken.Kind.EOF) {
                    next();
                    ElToken name = next();
                    return new ElNode.Function(token.text(), name.text(), token.start(), name.start(), name.end(),
                            List.of(), token.start(), name.end());
                }
                return new ElNode.Identifier(token.text(), token.start(), token.end());
            }
            case STRING -> {
                next();
                return new ElNode.Literal(ElNode.Literal.Kind.STRING, token.text(), token.start(), token.end());
            }
            case NUMBER -> {
                next();
                String text = token.text();
                if (text.contains(".") || text.contains("e") || text.contains("E")) {
                    return new ElNode.Literal(ElNode.Literal.Kind.DOUBLE, text, token.start(), token.end());
                }
                return new ElNode.Literal(ElNode.Literal.Kind.LONG, text, token.start(), token.end());
            }
            case KEYWORD -> {
                if (token.is("true") || token.is("false")) {
                    next();
                    return new ElNode.Literal(ElNode.Literal.Kind.BOOLEAN, token.text(), token.start(), token.end());
                }
                if (token.is("null")) {
                    next();
                    return new ElNode.Literal(ElNode.Literal.Kind.NULL, null, token.start(), token.end());
                }
                return missing();
            }
            case ERROR -> {
                next();
                problems.add(new Problem(token.text().length() > 1 || token.text().startsWith("'") || token.text().startsWith("\"")
                        ? "Unterminated string" : "Unexpected '" + token.text() + "'", token.start(), token.end()));
                return new ElNode.Missing(token.start(), token.end());
            }
            case EOF -> {
                return missing();
            }
            default -> {
                // operators
            }
        }
        if (token.is("(")) {
            next();
            ElNode inner = sequence();
            expect(")");
            return new ElNode.Parenthesized(inner, token.start(), lastEnd());
        }
        if (token.is("[")) {
            next();
            List<ElNode> elements = new ArrayList<>();
            if (!peek().is("]")) {
                while (true) {
                    elements.add(assignment());
                    if (!accept(",")) {
                        break;
                    }
                }
            }
            expect("]");
            return new ElNode.ListLiteral(elements, token.start(), lastEnd());
        }
        if (token.is("{")) {
            next();
            if (accept("}")) {
                return new ElNode.SetLiteral(List.of(), token.start(), lastEnd());
            }
            ElNode first = assignment();
            if (accept(":")) {
                List<ElNode> keys = new ArrayList<>();
                List<ElNode> values = new ArrayList<>();
                keys.add(first);
                values.add(assignment());
                while (accept(",")) {
                    keys.add(assignment());
                    expect(":");
                    values.add(assignment());
                }
                expect("}");
                return new ElNode.MapLiteral(keys, values, token.start(), lastEnd());
            }
            List<ElNode> elements = new ArrayList<>();
            elements.add(first);
            while (accept(",")) {
                elements.add(assignment());
            }
            expect("}");
            return new ElNode.SetLiteral(elements, token.start(), lastEnd());
        }
        return missing();
    }
}
