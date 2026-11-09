package io.github.jungm.fls.jdt;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * jdtls' own call hierarchy, so faces-ls can answer it for Java files and add the Facelets callers. Delegates to
 * {@code CallHierarchyHandler} (internal to jdtls, hence reflection) and moves each item's {@code data} to
 * {@code data.jdt}, next to {@code data.member}: the Java member the item denotes, for the EL usage search.
 */
final class CallHierarchy {
    private static final String HANDLER = "org.eclipse.jdt.ls.core.internal.handlers.CallHierarchyHandler";
    private static final String JSON_HANDLER = "org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler";
    private static final String JDT_UTILS = "org.eclipse.jdt.ls.core.internal.JDTUtils";

    private final Object handler;
    private final Object gson;
    private final Method toJson;
    private final Method fromJson;
    private final Method prepare;
    private final Method incoming;
    private final Method outgoing;
    private final Class<?> prepareParams;
    private final Class<?> incomingParams;
    private final Class<?> outgoingParams;

    private CallHierarchy() throws ReflectiveOperationException {
        ClassLoader loader = CallHierarchy.class.getClassLoader();
        Class<?> handlerClass = loader.loadClass(HANDLER);
        handler = handlerClass.getConstructor().newInstance();
        Class<?> jsonHandler = loader.loadClass(JSON_HANDLER);
        // lsp4j's Gson serializes enums such as SymbolKind as LSP numbers
        gson = jsonHandler.getMethod("getGson").invoke(jsonHandler.getConstructor(Map.class).newInstance(Map.of()));
        toJson = gson.getClass().getMethod("toJson", Object.class);
        fromJson = gson.getClass().getMethod("fromJson", String.class, Class.class);
        prepareParams = loader.loadClass("org.eclipse.lsp4j.CallHierarchyPrepareParams");
        incomingParams = loader.loadClass("org.eclipse.lsp4j.CallHierarchyIncomingCallsParams");
        outgoingParams = loader.loadClass("org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams");
        Class<?> monitor = loader.loadClass("org.eclipse.core.runtime.IProgressMonitor");
        prepare = handlerClass.getMethod("prepareCallHierarchy", prepareParams, monitor);
        incoming = handlerClass.getMethod("callHierarchyIncomingCalls", incomingParams, monitor);
        outgoing = handlerClass.getMethod("callHierarchyOutgoingCalls", outgoingParams, monitor);
    }

    private static volatile CallHierarchy instance;

    /** The bridge; fails when this jdtls lacks the handler (its internals changed). */
    static CallHierarchy get() {
        CallHierarchy current = instance;
        if (current == null) {
            try {
                current = new CallHierarchy();
                instance = current;
            } catch (ReflectiveOperationException | LinkageError e) {
                throw new IllegalStateException("jdtls call hierarchy not available", e);
            }
        }
        return current;
    }

    /** {@code {uri, line, character}}: the items at a position. */
    JsonElement prepare(JsonObject params) throws ReflectiveOperationException {
        JsonObject request = new JsonObject();
        JsonObject document = new JsonObject();
        document.add("uri", params.get("uri"));
        request.add("textDocument", document);
        JsonObject position = new JsonObject();
        position.add("line", params.get("line"));
        position.add("character", params.get("character"));
        request.add("position", position);
        JsonElement items = call(prepare, prepareParams, request);
        if (items instanceof JsonArray array) {
            array.forEach(item -> wrap(item.getAsJsonObject()));
        }
        return items;
    }

    /** {@code {item}}: the callers of an item from {@link #prepare} or from earlier calls. */
    JsonElement incoming(JsonObject params) throws ReflectiveOperationException {
        JsonElement calls = call(incoming, incomingParams, itemParams(params));
        if (calls instanceof JsonArray array) {
            array.forEach(call -> wrap(call.getAsJsonObject().getAsJsonObject("from")));
        }
        return calls;
    }

    /** {@code {item}}: what an item calls. */
    JsonElement outgoing(JsonObject params) throws ReflectiveOperationException {
        JsonElement calls = call(outgoing, outgoingParams, itemParams(params));
        if (calls instanceof JsonArray array) {
            array.forEach(call -> wrap(call.getAsJsonObject().getAsJsonObject("to")));
        }
        return calls;
    }

    private static JsonObject itemParams(JsonObject params) {
        JsonObject item = params.getAsJsonObject("item").deepCopy();
        // back to jdtls' own data
        if (item.get("data") instanceof JsonObject data && data.has("member")) {
            JsonElement jdt = data.get("jdt");
            if (jdt == null || jdt.isJsonNull()) {
                item.remove("data");
            } else {
                item.add("data", jdt);
            }
        }
        JsonObject request = new JsonObject();
        request.add("item", item);
        return request;
    }

    private JsonElement call(Method method, Class<?> paramsType, JsonObject params) throws ReflectiveOperationException {
        Object lspParams = fromJson.invoke(gson, params.toString(), paramsType);
        Object result;
        try {
            result = method.invoke(handler, lspParams, new NullProgressMonitor());
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("jdtls call hierarchy failed", e.getCause());
        }
        return result == null ? JsonNull.INSTANCE : JsonParser.parseString((String) toJson.invoke(gson, result));
    }

    private static void wrap(JsonObject item) {
        if (item == null) {
            return;
        }
        JsonObject data = new JsonObject();
        JsonElement original = item.get("data");
        data.add("jdt", original == null ? JsonNull.INSTANCE : original);
        JsonObject member = member(item);
        data.add("member", member == null ? JsonNull.INSTANCE : member);
        item.add("data", data);
    }

    /** {@code {declaringType, name, kind, parameterCount}} of the member an item's selection range starts at. */
    private static JsonObject member(JsonObject item) {
        try {
            ITypeRoot root = typeRoot(item.get("uri").getAsString());
            if (root == null || root.getSource() == null) {
                return null;
            }
            JsonObject start = item.getAsJsonObject("selectionRange").getAsJsonObject("start");
            int offset = offset(root.getSource(), start.get("line").getAsInt(), start.get("character").getAsInt());
            IJavaElement element = root.getElementAt(offset);
            JsonObject member = new JsonObject();
            switch (element) {
                case IMethod method -> {
                    member.addProperty("declaringType", method.getDeclaringType().getFullyQualifiedName('.'));
                    member.addProperty("name", method.getElementName());
                    member.addProperty("kind", method.isConstructor() ? "constructor" : "method");
                    member.addProperty("parameterCount", method.getNumberOfParameters());
                }
                case IField field -> {
                    member.addProperty("declaringType", field.getDeclaringType().getFullyQualifiedName('.'));
                    member.addProperty("name", field.getElementName());
                    member.addProperty("kind", "field");
                    member.addProperty("parameterCount", -1);
                }
                case IType type -> {
                    member.addProperty("declaringType", type.getFullyQualifiedName('.'));
                    member.addProperty("name", type.getElementName());
                    member.addProperty("kind", "type");
                    member.addProperty("parameterCount", -1);
                }
                case null, default -> {
                    return null;
                }
            }
            return member;
        } catch (JavaModelException | RuntimeException e) {
            return null;
        }
    }

    private static ITypeRoot typeRoot(String uri) {
        if (uri.startsWith("file:")) {
            IFile[] files = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(URI.create(uri));
            for (IFile file : files) {
                if (JavaCore.create(file) instanceof ITypeRoot root) {
                    return root;
                }
            }
            return null;
        }
        try {
            // jdt:// URIs of library classes
            Object classFile = CallHierarchy.class.getClassLoader().loadClass(JDT_UTILS)
                    .getMethod("resolveClassFile", String.class).invoke(null, uri);
            return classFile instanceof ITypeRoot root ? root : null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static int offset(String source, int line, int character) {
        int offset = 0;
        for (int current = 0; current < line && offset < source.length(); offset++) {
            if (source.charAt(offset) == '\n') {
                current++;
            }
        }
        return Math.min(offset + character, source.length());
    }
}
