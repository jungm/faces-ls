package io.github.jungm.fls.java;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Java type as written in qualified source form, e.g. {@code java.util.Map<java.lang.String,java.util.List<E>>},
 * {@code int[]}, {@code ? extends Foo} or a type variable {@code E}.
 *
 * @param name      erasure name (fully qualified) or type variable name
 * @param arguments type arguments
 * @param dimensions array dimensions
 */
public record JType(String name, List<JType> arguments, int dimensions) {
    public static final JType OBJECT = new JType("java.lang.Object", List.of(), 0);
    public static final JType STRING = new JType("java.lang.String", List.of(), 0);
    public static final JType BOOLEAN = new JType("java.lang.Boolean", List.of(), 0);
    public static final JType LONG = new JType("java.lang.Long", List.of(), 0);
    public static final JType DOUBLE = new JType("java.lang.Double", List.of(), 0);

    private static final Set<String> PRIMITIVES = Set.of("boolean", "byte", "char", "short", "int", "long", "float",
            "double", "void");

    private static final Map<String, String> BOXES = Map.of("boolean", "java.lang.Boolean", "byte", "java.lang.Byte",
            "char", "java.lang.Character", "short", "java.lang.Short", "int", "java.lang.Integer", "long",
            "java.lang.Long", "float", "java.lang.Float", "double", "java.lang.Double");

    public static JType of(String name) {
        return new JType(name, List.of(), 0);
    }

    public boolean isPrimitive() {
        return dimensions == 0 && PRIMITIVES.contains(name);
    }

    public boolean isArray() {
        return dimensions > 0;
    }

    public boolean isVoid() {
        return dimensions == 0 && name.equals("void");
    }

    /** Type variables are bare identifiers without a package, e.g. {@code E} or {@code T}. */
    public boolean isTypeVariable() {
        return !name.contains(".") && !isPrimitive() && !name.startsWith("?");
    }

    public JType boxed() {
        if (dimensions == 0 && BOXES.containsKey(name)) {
            return of(BOXES.get(name));
        }
        return this;
    }

    public JType componentType() {
        return dimensions == 0 ? null : new JType(name, arguments, dimensions - 1);
    }

    /** Replaces type variables using the map; unknown variables erase to {@code Object}. */
    public JType substitute(Map<String, JType> bindings) {
        if (name.startsWith("?")) {
            JType bound = upperBound();
            return bound.substitute(bindings);
        }
        if (isTypeVariable()) {
            JType bound = bindings.get(name);
            JType resolved = bound != null ? bound : OBJECT;
            return dimensions == 0 ? resolved : new JType(resolved.name, resolved.arguments, resolved.dimensions + dimensions);
        }
        if (arguments.isEmpty()) {
            return this;
        }
        List<JType> substituted = new ArrayList<>(arguments.size());
        for (JType argument : arguments) {
            substituted.add(argument.substitute(bindings));
        }
        return new JType(name, substituted, dimensions);
    }

    /** {@code ? extends X} becomes X; {@code ?} and {@code ? super X} become Object. */
    public JType upperBound() {
        if (name.startsWith("? extends ")) {
            return parse(name.substring("? extends ".length()));
        }
        if (name.startsWith("?")) {
            return OBJECT;
        }
        return this;
    }

    public String simpleName() {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    /** Display form with simple names, e.g. {@code List<User>}. */
    public String display() {
        StringBuilder text = new StringBuilder(simpleName());
        if (!arguments.isEmpty()) {
            text.append('<');
            for (int i = 0; i < arguments.size(); i++) {
                if (i > 0) {
                    text.append(", ");
                }
                text.append(arguments.get(i).display());
            }
            text.append('>');
        }
        text.append("[]".repeat(dimensions));
        return text.toString();
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder(name);
        if (!arguments.isEmpty()) {
            text.append('<');
            for (int i = 0; i < arguments.size(); i++) {
                if (i > 0) {
                    text.append(',');
                }
                text.append(arguments.get(i));
            }
            text.append('>');
        }
        text.append("[]".repeat(dimensions));
        return text.toString();
    }

    public static JType parse(String text) {
        return new Parser(text.trim()).type();
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        JType type() {
            skipSpaces();
            if (pos < text.length() && text.charAt(pos) == '?') {
                pos++;
                skipSpaces();
                if (text.startsWith("extends", pos)) {
                    pos += "extends".length();
                    JType bound = type();
                    return new JType("? extends " + bound, List.of(), 0);
                }
                if (text.startsWith("super", pos)) {
                    pos += "super".length();
                    JType bound = type();
                    return new JType("? super " + bound, List.of(), 0);
                }
                return new JType("?", List.of(), 0);
            }
            int start = pos;
            while (pos < text.length() && (Character.isJavaIdentifierPart(text.charAt(pos)) || text.charAt(pos) == '.'
                    || text.charAt(pos) == '$')) {
                pos++;
            }
            String name = text.substring(start, pos).replace('$', '.');
            List<JType> arguments = new ArrayList<>();
            skipSpaces();
            if (pos < text.length() && text.charAt(pos) == '<') {
                pos++;
                while (pos < text.length()) {
                    arguments.add(type());
                    skipSpaces();
                    if (pos < text.length() && text.charAt(pos) == ',') {
                        pos++;
                        continue;
                    }
                    if (pos < text.length() && text.charAt(pos) == '>') {
                        pos++;
                    }
                    break;
                }
            }
            // a nested type of a parameterized type: Outer<T>.Inner
            skipSpaces();
            if (pos < text.length() && text.charAt(pos) == '.') {
                pos++;
                JType inner = type();
                return new JType(name + "." + inner.name, inner.arguments, inner.dimensions);
            }
            int dimensions = 0;
            while (true) {
                skipSpaces();
                if (text.startsWith("[]", pos)) {
                    dimensions++;
                    pos += 2;
                } else if (text.startsWith("...", pos)) {
                    dimensions++;
                    pos += 3;
                } else {
                    break;
                }
            }
            return new JType(name.isEmpty() ? "java.lang.Object" : name, List.copyOf(arguments), dimensions);
        }

        private void skipSpaces() {
            while (pos < text.length() && text.charAt(pos) == ' ') {
                pos++;
            }
        }
    }
}
