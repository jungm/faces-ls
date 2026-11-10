package io.github.jungm.fls.el;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * EL syntax tree. All offsets are absolute document offsets.
 */
public sealed interface ElNode {
    int start();

    int end();

    default boolean contains(int offset) {
        return offset >= start() && offset <= end();
    }

    /** Visits this node and all descendants, parents first. */
    default void walk(Consumer<ElNode> visitor) {
        visitor.accept(this);
        for (ElNode child : children()) {
            if (child != null) {
                child.walk(visitor);
            }
        }
    }

    List<ElNode> children();

    record Identifier(String name, int start, int end) implements ElNode {
        public List<ElNode> children() {
            return List.of();
        }
    }

    /** {@code target.name}; {@code name} is empty while the user has only typed the dot. */
    record Member(ElNode target, String name, int nameStart, int nameEnd, int start, int end) implements ElNode {
        public List<ElNode> children() {
            return List.of(target);
        }
    }

    /** {@code target[index]}; {@code index} is null when incomplete. */
    record Index(ElNode target, ElNode index, int start, int end) implements ElNode {
        public List<ElNode> children() {
            return index == null ? List.of(target) : List.of(target, index);
        }
    }

    /** A call on a member ({@code bean.save(x)}), an identifier ({@code lambda(1)}) or another expression. */
    record Call(ElNode target, List<ElNode> arguments, int start, int end) implements ElNode {
        public List<ElNode> children() {
            return concat(target, arguments);
        }
    }

    /** {@code prefix:name(args)}: a tag library function. */
    record Function(String prefix, String name, int prefixStart, int nameStart, int nameEnd, List<ElNode> arguments,
                    int start, int end) implements ElNode {
        public List<ElNode> children() {
            return List.copyOf(arguments);
        }
    }

    record Literal(Kind kind, Object value, int start, int end) implements ElNode {
        public enum Kind {
            STRING, LONG, DOUBLE, BOOLEAN, NULL
        }

        public List<ElNode> children() {
            return List.of();
        }
    }

    record Unary(String operator, ElNode operand, int start, int end) implements ElNode {
        public List<ElNode> children() {
            return operand == null ? List.of() : List.of(operand);
        }
    }

    record Binary(String operator, ElNode left, ElNode right, int start, int end) implements ElNode {
        public List<ElNode> children() {
            return right == null ? List.of(left) : List.of(left, right);
        }
    }

    record Ternary(ElNode condition, ElNode consequence, ElNode alternative, int start, int end) implements ElNode {
        public List<ElNode> children() {
            return Stream.of(condition, consequence, alternative).filter(Objects::nonNull).toList();
        }
    }

    record Lambda(List<Identifier> parameters, ElNode body, int start, int end) implements ElNode {
        public List<ElNode> children() {
            return body == null ? List.copyOf(parameters) : concat(body, List.copyOf(parameters));
        }
    }

    record Assignment(ElNode target, ElNode value, int start, int end) implements ElNode {
        public List<ElNode> children() {
            return value == null ? List.of(target) : List.of(target, value);
        }
    }

    record Sequence(List<ElNode> expressions, int start, int end) implements ElNode {
        public List<ElNode> children() {
            return List.copyOf(expressions);
        }
    }

    record ListLiteral(List<ElNode> elements, int start, int end) implements ElNode {
        public List<ElNode> children() {
            return List.copyOf(elements);
        }
    }

    record SetLiteral(List<ElNode> elements, int start, int end) implements ElNode {
        public List<ElNode> children() {
            return List.copyOf(elements);
        }
    }

    record MapLiteral(List<ElNode> keys, List<ElNode> values, int start, int end) implements ElNode {
        public List<ElNode> children() {
            List<ElNode> all = new ArrayList<>(keys);
            all.addAll(values);
            return all;
        }
    }

    record Parenthesized(ElNode expression, int start, int end) implements ElNode {
        public List<ElNode> children() {
            return expression == null ? List.of() : List.of(expression);
        }
    }

    /** Placeholder where an expression is missing; lets completion work on {@code a + |}. */
    record Missing(int start, int end) implements ElNode {
        public List<ElNode> children() {
            return List.of();
        }
    }

    private static List<ElNode> concat(ElNode first, List<ElNode> rest) {
        List<ElNode> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }
}
