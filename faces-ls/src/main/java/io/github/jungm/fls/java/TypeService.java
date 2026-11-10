package io.github.jungm.fls.java;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.jungm.fls.Threads;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Java type information from faces-jdt with type-argument substitution across the type hierarchy, so that
 * {@code List<User>.get(int)} yields {@code User}. Answers are cached until jdtls reports a Java change; a missing
 * answer (no connection, timeout, error) is not, so the next lookup asks again.
 * <p>
 * After a change, a type that was known before is answered from the previous answer at once while faces-jdt is asked
 * again in the background ({@code onRevalidated} runs once fresh answers have arrived), so that a page opened after
 * a Java edit does not wait for jdtls before it can be highlighted.
 */
public final class TypeService {

    public record MethodInfo(String name, List<JType> parameters, List<String> parameterNames, JType returnType,
                             boolean isStatic, boolean varargs, boolean deprecated, String declaringType) {
        public String signature() {
            StringBuilder text = new StringBuilder(name).append('(');
            for (int i = 0; i < parameters.size(); i++) {
                if (i > 0) {
                    text.append(", ");
                }
                text.append(parameters.get(i).display());
                if (parameterNames != null && i < parameterNames.size()) {
                    text.append(' ').append(parameterNames.get(i));
                }
            }
            return text.append(')').toString();
        }
    }

    public record FieldInfo(String name, JType type, boolean isStatic, boolean enumConstant, String declaringType) {
    }

    public record Property(String name, JType type, MethodInfo getter, MethodInfo setter) {
        public boolean readable() {
            return getter != null;
        }

        public String declaringType() {
            return getter != null ? getter.declaringType() : setter.declaringType();
        }
    }

    public record TypeInfo(String fqn, String kind, boolean source, List<String> typeParameters, List<JType> supertypes,
                           List<MethodInfo> methods, List<FieldInfo> fields, List<String> recordComponents) {
    }

    /** Members visible on a type, with type arguments substituted. Properties follow JavaBeans/EL rules. */
    public record Members(JType type, Map<String, Property> properties, List<MethodInfo> methods, List<FieldInfo> fields) {
        public List<MethodInfo> methods(String name) {
            return methods.stream().filter(m -> m.name().equals(name)).toList();
        }
    }

    private static final long TYPE_TIMEOUT_MILLIS = 8000;

    /** Background lookups of types whose previous answer was served, for all projects. */
    private static final ExecutorService REVALIDATION =
            Executors.newFixedThreadPool(2, Threads.daemon("faces-ls-types"));

    private final JdtClient client;
    private final String projectPath;
    private final Runnable onRevalidated;
    /**
     * The caches of the current generation. {@link #invalidate()} replaces them, and a computation stores its result
     * in the caches it started with, so that answers to requests sent before an invalidation are not cached.
     */
    private volatile Caches caches = new Caches();
    /** Answers from before the last invalidation, served while the type is asked for again. */
    private volatile Map<String, Optional<TypeInfo>> previous = Map.of();

    private static final class Caches {
        /** Answers of faces-jdt by type name: empty for types it reported as not existing. */
        final Map<String, Optional<TypeInfo>> infos = new ConcurrentHashMap<>();
        final Map<String, Members> members = new ConcurrentHashMap<>();
        /** Types asked for in the background; removed again when faces-jdt gave no answer. */
        final Set<String> revalidating = ConcurrentHashMap.newKeySet();
        final AtomicInteger revalidationsRunning = new AtomicInteger();
        /** Whether a background lookup got an answer since {@code onRevalidated} last ran. */
        final AtomicBoolean revalidated = new AtomicBoolean();
    }

    /** Tracks whether every type lookup of a computation got an answer, i.e. whether its result may be cached. */
    private static final class Lookups {
        boolean complete = true;
    }

    public TypeService(JdtClient client, String projectPath) {
        this(client, projectPath, () -> { });
    }

    /** @param onRevalidated runs when fresh answers have replaced previous ones served after an invalidation */
    public TypeService(JdtClient client, String projectPath, Runnable onRevalidated) {
        this.client = client;
        this.projectPath = projectPath;
        this.onRevalidated = onRevalidated;
    }

    public boolean available() {
        return client != null && client.connected();
    }

    /** Drops the cached answers; the ones that existed are served until faces-jdt has answered again. */
    public synchronized void invalidate() {
        Map<String, Optional<TypeInfo>> known = new HashMap<>(previous);
        known.putAll(caches.infos);
        previous = Map.copyOf(known);
        caches = new Caches();
    }

    private JsonObject params() {
        JsonObject params = new JsonObject();
        params.addProperty("path", projectPath);
        return params;
    }

    /** The description of a type, or null when it does not exist or faces-jdt gave no answer. */
    public TypeInfo info(String fqn) {
        return info(fqn, new Lookups());
    }

    private TypeInfo info(String fqn, Lookups lookups) {
        Optional<TypeInfo> answer = lookup(fqn, lookups);
        if (answer == null) {
            lookups.complete = false;
            return null;
        }
        return answer.orElse(null);
    }

    /**
     * The cached, previous or requested answer for a type: empty when faces-jdt reported that the type does not exist,
     * null when it gave no answer. A previous answer leaves {@code lookups} incomplete, so that nothing computed from
     * it is cached.
     */
    private Optional<TypeInfo> lookup(String fqn, Lookups lookups) {
        if (!available()) {
            return null;
        }
        Caches current = caches;
        Optional<TypeInfo> cached = current.infos.get(fqn);
        if (cached != null) {
            return cached;
        }
        Optional<TypeInfo> known = previous.get(fqn);
        if (known != null) {
            lookups.complete = false;
            revalidate(current, fqn);
            return known;
        }
        return fetch(current, fqn);
    }

    private void revalidate(Caches current, String fqn) {
        if (!current.revalidating.add(fqn)) {
            return;
        }
        current.revalidationsRunning.incrementAndGet();
        REVALIDATION.execute(() -> {
            try {
                if (caches == current && fetch(current, fqn) != null) {
                    current.revalidated.set(true);
                } else {
                    current.revalidating.remove(fqn);
                }
            } catch (RuntimeException e) {
                current.revalidating.remove(fqn);
            } finally {
                // one notification per burst; only answers make one, so a failing lookup does not loop
                if (current.revalidationsRunning.decrementAndGet() == 0 && caches == current
                        && current.revalidated.getAndSet(false)) {
                    onRevalidated.run();
                }
            }
        });
    }

    /**
     * Asks faces-jdt for a type and caches the answer in {@code current}. One request returns the type together with
     * all its supertypes, which are cached as well.
     */
    private Optional<TypeInfo> fetch(Caches current, String fqn) {
        JsonObject params = params();
        params.addProperty("fqn", fqn);
        Optional<JsonElement> response = client.query("type", params, TYPE_TIMEOUT_MILLIS);
        if (response.isEmpty()) {
            return null;
        }
        Map<String, Optional<TypeInfo>> answered = new HashMap<>();
        Optional<TypeInfo> result = Optional.empty();
        if (response.get() instanceof JsonArray descriptions) {
            for (JsonElement description : descriptions) {
                TypeInfo info = toInfo(description.getAsJsonObject());
                if (result.isEmpty()) {
                    result = Optional.of(info);
                }
                answered.putIfAbsent(info.fqn(), Optional.of(info));
            }
        }
        answered.put(fqn, result);
        current.infos.putAll(answered);
        return result;
    }

    private static TypeInfo toInfo(JsonObject json) {
        String fqn = json.get("fqn").getAsString();
        List<String> typeParameters = strings(json.getAsJsonArray("typeParameters"));
        List<JType> supertypes = new ArrayList<>();
        for (String supertype : strings(json.getAsJsonArray("supertypes"))) {
            supertypes.add(JType.parse(supertype));
        }
        List<MethodInfo> methods = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("methods")) {
            JsonObject m = element.getAsJsonObject();
            List<JType> parameters = new ArrayList<>();
            for (String parameter : strings(m.getAsJsonArray("params"))) {
                parameters.add(JType.parse(parameter));
            }
            methods.add(new MethodInfo(m.get("name").getAsString(), parameters,
                    m.has("paramNames") ? strings(m.getAsJsonArray("paramNames")) : null,
                    JType.parse(m.get("returnType").getAsString()), m.get("static").getAsBoolean(),
                    m.get("varargs").getAsBoolean(), m.get("deprecated").getAsBoolean(), fqn));
        }
        List<FieldInfo> fields = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("fields")) {
            JsonObject f = element.getAsJsonObject();
            fields.add(new FieldInfo(f.get("name").getAsString(), JType.parse(f.get("type").getAsString()),
                    f.get("static").getAsBoolean(), f.get("enumConstant").getAsBoolean(), fqn));
        }
        List<String> recordComponents = json.has("recordComponents") ? strings(json.getAsJsonArray("recordComponents")) : List.of();
        return new TypeInfo(fqn, json.get("kind").getAsString(), json.get("source").getAsBoolean(), typeParameters,
                supertypes, methods, fields, recordComponents);
    }

    private static List<String> strings(JsonArray array) {
        List<String> result = new ArrayList<>();
        if (array != null) {
            for (JsonElement element : array) {
                result.add(element.getAsString());
            }
        }
        return result;
    }

    /** Walks the hierarchy breadth-first, yielding each supertype parameterized as seen from {@code type}. */
    public List<JType> hierarchy(JType type) {
        return hierarchy(type, new Lookups());
    }

    private List<JType> hierarchy(JType type, Lookups lookups) {
        List<JType> result = new ArrayList<>();
        Deque<JType> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            JType current = queue.poll();
            if (!seen.add(current.name())) {
                continue;
            }
            result.add(current);
            TypeInfo info = info(current.name(), lookups);
            if (info == null) {
                continue;
            }
            Map<String, JType> bindings = bindings(info, current);
            for (JType supertype : info.supertypes()) {
                queue.add(supertype.substitute(bindings));
            }
        }
        return result;
    }

    private static Map<String, JType> bindings(TypeInfo info, JType type) {
        Map<String, JType> bindings = new HashMap<>();
        List<String> parameters = info.typeParameters();
        for (int i = 0; i < parameters.size(); i++) {
            JType argument = i < type.arguments().size() ? type.arguments().get(i).upperBound() : JType.OBJECT;
            bindings.put(parameters.get(i), argument);
        }
        return bindings;
    }

    /** The parameterization of {@code targetFqn} in the hierarchy of {@code type}, e.g. Iterable&lt;User&gt;. */
    public JType asSupertype(JType type, String targetFqn) {
        return asSupertype(type, targetFqn, new Lookups());
    }

    private JType asSupertype(JType type, String targetFqn, Lookups lookups) {
        if (type.isArray()) {
            return null;
        }
        for (JType supertype : hierarchy(type, lookups)) {
            if (supertype.name().equals(targetFqn)) {
                return supertype;
            }
        }
        return null;
    }

    public boolean isSubtype(JType type, String targetFqn) {
        return asSupertype(type, targetFqn) != null;
    }

    public Members members(JType type) {
        if (type.isArray() || type.isPrimitive()) {
            return new Members(type, Map.of(), List.of(), List.of());
        }
        String key = type.toString();
        Caches generation = caches;
        Members cached = generation.members.get(key);
        if (cached != null) {
            return cached;
        }
        Lookups lookups = new Lookups();
        Map<String, MethodInfo> methodsBySignature = new LinkedHashMap<>();
        // all overloads, for the setters of properties
        Map<String, MethodInfo> methodsByErasure = new LinkedHashMap<>();
        Map<String, FieldInfo> fieldsByName = new LinkedHashMap<>();
        List<String> recordComponents = new ArrayList<>();
        for (JType current : hierarchy(type, lookups)) {
            TypeInfo info = info(current.name(), lookups);
            if (info == null) {
                continue;
            }
            if (current == type || current.name().equals(type.name())) {
                recordComponents.addAll(info.recordComponents());
            }
            Map<String, JType> bindings = bindings(info, current);
            for (MethodInfo method : info.methods()) {
                List<JType> parameters = new ArrayList<>();
                for (JType parameter : method.parameters()) {
                    parameters.add(parameter.substitute(bindings));
                }
                MethodInfo substituted = new MethodInfo(method.name(), parameters, method.parameterNames(),
                        method.returnType().substitute(bindings), method.isStatic(), method.varargs(), method.deprecated(),
                        method.declaringType());
                methodsBySignature.putIfAbsent(method.name() + "/" + parameters.size(), substituted);
                methodsByErasure.putIfAbsent(erasedSignature(substituted), substituted);
            }
            for (FieldInfo field : info.fields()) {
                fieldsByName.putIfAbsent(field.name(), new FieldInfo(field.name(), field.type().substitute(bindings),
                        field.isStatic(), field.enumConstant(), field.declaringType()));
            }
        }
        Members result = new Members(type, properties(methodsByErasure.values(), recordComponents, lookups),
                List.copyOf(methodsBySignature.values()), List.copyOf(fieldsByName.values()));
        if (lookups.complete) {
            generation.members.put(key, result);
        }
        return result;
    }

    private static String erasedSignature(MethodInfo method) {
        StringBuilder signature = new StringBuilder(method.name()).append('(');
        for (JType parameter : method.parameters()) {
            signature.append(parameter.name()).append("[]".repeat(parameter.dimensions())).append(',');
        }
        return signature.append(')').toString();
    }

    /**
     * Properties as {@code java.beans.Introspector} (and so Jakarta EL's {@code BeanELResolver}) finds them:
     * <ul>
     * <li>a getter is a public instance method without parameters, {@code getX()} returning anything but void, or
     * {@code isX()} returning primitive {@code boolean}, which wins over {@code getX()}; record components count
     * as getters too;</li>
     * <li>a setter is {@code void setX(T)}. With a getter, only setters whose parameter type is assignable to the
     * getter's type belong to the property, the most specific one of them; without such a setter the property is
     * read-only. Without a getter, the first setter makes a write-only property.</li>
     * </ul>
     */
    private Map<String, Property> properties(Collection<MethodInfo> methods, List<String> recordComponents, Lookups lookups) {
        Map<String, MethodInfo> isGetters = new LinkedHashMap<>();
        Map<String, MethodInfo> getGetters = new LinkedHashMap<>();
        Map<String, MethodInfo> componentGetters = new LinkedHashMap<>();
        Map<String, List<MethodInfo>> setters = new LinkedHashMap<>();
        for (MethodInfo method : methods) {
            if (method.isStatic()) {
                continue;
            }
            String name = method.name();
            int parameterCount = method.parameters().size();
            JType returnType = method.returnType();
            if (parameterCount == 0 && !returnType.isVoid()) {
                if (name.startsWith("is") && name.length() > 2 && returnType.dimensions() == 0
                        && returnType.name().equals("boolean")) {
                    isGetters.putIfAbsent(decapitalize(name.substring(2)), method);
                } else if (name.startsWith("get") && name.length() > 3) {
                    getGetters.putIfAbsent(decapitalize(name.substring(3)), method);
                } else if (recordComponents.contains(name)) {
                    componentGetters.putIfAbsent(name, method);
                }
            } else if (parameterCount == 1 && returnType.isVoid() && name.startsWith("set") && name.length() > 3) {
                setters.computeIfAbsent(decapitalize(name.substring(3)), key -> new ArrayList<>()).add(method);
            }
        }
        Map<String, MethodInfo> getters = new LinkedHashMap<>(isGetters);
        getGetters.forEach(getters::putIfAbsent);
        componentGetters.forEach(getters::putIfAbsent);

        Map<String, Property> properties = new LinkedHashMap<>();
        for (Map.Entry<String, MethodInfo> getter : getters.entrySet()) {
            JType type = getter.getValue().returnType();
            MethodInfo setter = null;
            for (MethodInfo candidate : setters.getOrDefault(getter.getKey(), List.of())) {
                JType parameter = candidate.parameters().get(0);
                if (assignable(parameter, type, lookups)
                        && (setter == null || assignable(parameter, setter.parameters().get(0), lookups))) {
                    setter = candidate;
                }
            }
            properties.put(getter.getKey(), new Property(getter.getKey(), type, getter.getValue(), setter));
        }
        for (Map.Entry<String, List<MethodInfo>> setter : setters.entrySet()) {
            MethodInfo first = setter.getValue().get(0);
            properties.computeIfAbsent(setter.getKey(), name -> new Property(name, first.parameters().get(0), null, first));
        }
        return properties;
    }

    /** Whether a value of type {@code from} can be assigned to {@code to}, by erasure as {@code Class.isAssignableFrom}. */
    private boolean assignable(JType from, JType to, Lookups lookups) {
        if (from.isPrimitive() || to.isPrimitive()) {
            return from.dimensions() == to.dimensions() && from.name().equals(to.name());
        }
        if (from.dimensions() == to.dimensions() && from.name().equals(to.name())) {
            return true;
        }
        if (to.dimensions() == 0) {
            if (to.name().equals("java.lang.Object")) {
                return true;
            }
            if (from.isArray()) {
                return to.name().equals("java.lang.Cloneable") || to.name().equals("java.io.Serializable");
            }
            return asSupertype(JType.of(from.name()), to.name(), lookups) != null;
        }
        if (from.dimensions() < to.dimensions()) {
            return false;
        }
        // arrays are covariant: compare the components left after removing the target's dimensions
        return assignable(new JType(from.name(), List.of(), from.dimensions() - to.dimensions()), JType.of(to.name()), lookups);
    }

    /** {@link java.beans.Introspector#decapitalize}. */
    public static String decapitalize(String name) {
        if (name.isEmpty()) {
            return name;
        }
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1)) && Character.isUpperCase(name.charAt(0))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    public record SourceLocation(String uri, int startLine, int startCharacter, int endLine, int endCharacter) {
    }

    public SourceLocation location(String fqn, String kind, String name, int parameterCount) {
        if (!available()) {
            return null;
        }
        JsonObject params = params();
        params.addProperty("fqn", fqn);
        params.addProperty("kind", kind);
        if (name != null) {
            params.addProperty("name", name);
        }
        params.addProperty("paramCount", parameterCount);
        JsonElement response = client.request("location", params);
        return toLocation(response);
    }

    public static SourceLocation toLocation(JsonElement response) {
        if (response == null || !response.isJsonObject()) {
            return null;
        }
        JsonObject location = response.getAsJsonObject();
        JsonObject range = location.getAsJsonObject("range");
        JsonObject start = range.getAsJsonObject("start");
        JsonObject end = range.getAsJsonObject("end");
        return new SourceLocation(location.get("uri").getAsString(), start.get("line").getAsInt(),
                start.get("character").getAsInt(), end.get("line").getAsInt(), end.get("character").getAsInt());
    }

    public String javadoc(String fqn, String kind, String name, int parameterCount) {
        if (!available()) {
            return null;
        }
        JsonObject params = params();
        params.addProperty("fqn", fqn);
        params.addProperty("kind", kind);
        if (name != null) {
            params.addProperty("name", name);
        }
        params.addProperty("paramCount", parameterCount);
        JsonElement response = client.request("javadoc", params, 3000);
        if (response.isJsonObject() && response.getAsJsonObject().has("markdown")) {
            return response.getAsJsonObject().get("markdown").getAsString();
        }
        return null;
    }

    public List<String> findTypes(String prefix, int limit) {
        List<String> result = new ArrayList<>();
        if (!available()) {
            return result;
        }
        JsonObject params = params();
        params.addProperty("prefix", prefix);
        params.addProperty("limit", limit);
        JsonElement response = client.request("findTypes", params, 4000);
        if (response.isJsonArray()) {
            for (JsonElement element : response.getAsJsonArray()) {
                result.add(element.getAsJsonObject().get("fqn").getAsString());
            }
        }
        return result;
    }
}
