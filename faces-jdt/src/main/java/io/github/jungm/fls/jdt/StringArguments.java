package io.github.jungm.fls.jdt;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.TextBlock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The arguments of method calls that have a constant {@code String} value: literals, constants and concatenations of
 * them. Frameworks name things by such strings (message keys, configuration keys); extensions of faces-ls resolve
 * them.
 */
final class StringArguments {
    private StringArguments() {
    }

    /** The arguments of a whole file as of {@code source}, the text jdtls had for it. */
    record Cached(String source, JsonElement arguments) {
    }

    /**
     * {@code {uri, line?, character?, receiverTypes?}}: {@code [{value, range, literal, method, index, argumentCount,
     * receiverTypes}]}. With a position, only the argument a string literal at that position belongs to; without, all
     * arguments of the file. {@code receiverTypes} (fully qualified names) limits the answer to calls on one of
     * these types or their subtypes; in the answer it lists the type the method is called on and its supertypes.
     *
     * @param cache   the arguments of whole files by request, which editors ask for again whenever a file scrolls;
     *                an answer is good for the text it was computed from
     * @param current false once the Java model changed while the answer was computed, which is then not kept
     */
    static JsonElement find(JsonObject params, Map<String, Cached> cache, BooleanSupplier current) throws JavaModelException {
        JsonArray result = new JsonArray();
        ITypeRoot root = CallHierarchy.typeRoot(params.get("uri").getAsString());
        String source = root == null ? null : root.getSource();
        if (source == null) {
            return result;
        }
        boolean wholeFile = !(params.has("line") && params.has("character"));
        String key = params.toString();
        Cached cached = wholeFile ? cache.get(key) : null;
        if (cached != null && cached.source().equals(source)) {
            return cached.arguments();
        }
        find(params, root, source, result);
        if (wholeFile && current.getAsBoolean()) {
            if (cache.size() >= 32) {
                cache.clear();
            }
            cache.put(key, new Cached(source, result));
        }
        return result;
    }

    private static void find(JsonObject params, ITypeRoot root, String source, JsonArray result) {
        Set<String> filter = new HashSet<>();
        if (params.get("receiverTypes") instanceof JsonArray types) {
            types.forEach(type -> filter.add(type.getAsString()));
        }
        boolean atPosition = params.has("line") && params.has("character");
        int offset = atPosition ? SourcePositions.offset(source, params.get("line").getAsInt(),
                params.get("character").getAsInt()) : -1;

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(root);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        if (atPosition) {
            // only the method around the position gets a body
            parser.setFocalPosition(offset);
        }
        if (!(parser.createAST(null) instanceof CompilationUnit unit)) {
            return;
        }
        if (atPosition) {
            ASTNode node = NodeFinder.perform(unit, offset, 0);
            if (node instanceof StringLiteral || node instanceof TextBlock) {
                describeArgument(unit, (Expression) node, filter, result);
            }
            return;
        }
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation call) {
                describeCall(unit, call, call.arguments(), filter, result);
                return true;
            }

            @Override
            public boolean visit(SuperMethodInvocation call) {
                describeCall(unit, call, call.arguments(), filter, result);
                return true;
            }
        });
    }

    /** The argument that {@code literal} is, or is a part of ({@code "a." + B}), when it is the argument of a call. */
    private static void describeArgument(CompilationUnit unit, Expression literal, Set<String> filter, JsonArray result) {
        Expression argument = literal;
        while (argument.getParent() instanceof InfixExpression || argument.getParent() instanceof ParenthesizedExpression) {
            argument = (Expression) argument.getParent();
        }
        ASTNode call = argument.getParent();
        List<?> arguments = call instanceof MethodInvocation invocation ? invocation.arguments()
                : call instanceof SuperMethodInvocation invocation ? invocation.arguments() : null;
        int index = arguments == null ? -1 : arguments.indexOf(argument);
        if (index >= 0) {
            describe(unit, call, arguments.size(), argument, index, filter, result);
        }
    }

    private static void describeCall(CompilationUnit unit, ASTNode call, List<?> arguments, Set<String> filter, JsonArray result) {
        for (int i = 0; i < arguments.size(); i++) {
            if (arguments.get(i) instanceof Expression argument && mayBeString(argument)) {
                describe(unit, call, arguments.size(), argument, i, filter, result);
            }
        }
    }

    /** Leaves out what cannot be a string constant, before any binding is resolved. */
    private static boolean mayBeString(Expression argument) {
        return switch (argument.getNodeType()) {
            case ASTNode.STRING_LITERAL, ASTNode.TEXT_BLOCK, ASTNode.SIMPLE_NAME, ASTNode.QUALIFIED_NAME,
                    ASTNode.INFIX_EXPRESSION, ASTNode.PARENTHESIZED_EXPRESSION, ASTNode.FIELD_ACCESS -> true;
            default -> false;
        };
    }

    private static void describe(CompilationUnit unit, ASTNode call, int argumentCount, Expression argument, int index,
                                 Set<String> filter, JsonArray result) {
        IMethodBinding method;
        Expression target = null;
        if (call instanceof MethodInvocation invocation) {
            method = invocation.resolveMethodBinding();
            target = invocation.getExpression();
        } else {
            method = ((SuperMethodInvocation) call).resolveMethodBinding();
        }
        if (method == null) {
            return;
        }
        ITypeBinding receiver = target == null ? null : target.resolveTypeBinding();
        Set<String> receiverTypes = hierarchy(receiver != null ? receiver : method.getDeclaringClass());
        if (!filter.isEmpty() && receiverTypes.stream().noneMatch(filter::contains)) {
            return;
        }
        if (!(argument.resolveConstantExpressionValue() instanceof String value)) {
            return;
        }
        JsonObject described = new JsonObject();
        described.addProperty("value", value);
        JsonObject range = new JsonObject();
        range.add("start", position(unit, argument.getStartPosition()));
        range.add("end", position(unit, argument.getStartPosition() + argument.getLength()));
        described.add("range", range);
        described.addProperty("literal", argument instanceof StringLiteral || argument instanceof TextBlock);
        described.addProperty("method", method.getName());
        described.addProperty("index", index);
        described.addProperty("argumentCount", argumentCount);
        JsonArray types = new JsonArray();
        receiverTypes.forEach(types::add);
        described.add("receiverTypes", types);
        result.add(described);
    }

    /** The erased, fully qualified names of a type and all its supertypes, the type first. */
    private static Set<String> hierarchy(ITypeBinding type) {
        Set<String> names = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        Deque<ITypeBinding> queue = new ArrayDeque<>();
        if (type != null) {
            queue.add(type);
        }
        while (!queue.isEmpty()) {
            ITypeBinding current = queue.poll().getErasure();
            if (current == null || !seen.add(current.getKey())) {
                continue;
            }
            // anonymous and local classes have no qualified name
            if (!current.getQualifiedName().isEmpty()) {
                names.add(current.getQualifiedName());
            }
            if (current.getSuperclass() != null) {
                queue.add(current.getSuperclass());
            }
            for (ITypeBinding implemented : current.getInterfaces()) {
                queue.add(implemented);
            }
        }
        return names;
    }

    private static JsonObject position(CompilationUnit unit, int offset) {
        JsonObject position = new JsonObject();
        position.addProperty("line", Math.max(0, unit.getLineNumber(offset) - 1));
        position.addProperty("character", Math.max(0, unit.getColumnNumber(offset)));
        return position;
    }
}
