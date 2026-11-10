package io.github.jungm.fls.jdt;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IAnnotatable;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMemberValuePair;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * Answers faces-ls queries from the jdtls Java model. Types are described from DOM bindings, so member types
 * are fully qualified and keep their type arguments (type variables of the declaring type stay as names).
 */
final class JavaModelService implements JavaQueries {
    private static final List<String> NAMED_ANNOTATIONS = List.of(
            "jakarta.inject.Named", "javax.inject.Named",
            "jakarta.enterprise.inject.Model", "javax.enterprise.inject.Model",
            "jakarta.faces.bean.ManagedBean", "javax.faces.bean.ManagedBean");

    private static final Set<String> SCOPE_ANNOTATIONS = Set.of(
            "RequestScoped", "SessionScoped", "ApplicationScoped", "ConversationScoped", "Dependent",
            "ViewScoped", "FlowScoped", "NoneScoped", "CustomScoped", "Model", "ClientWindowScoped");

    /** Type descriptions by project and fully qualified name; {@link JsonNull} for types that do not exist. */
    private final Map<String, JsonElement> typeCache = new ConcurrentHashMap<>();
    private final Path sourcesDir;
    private final Map<String, JsonElement> beanCache = new ConcurrentHashMap<>();
    /** Increments on {@link #invalidate()}; results computed across an invalidation are not cached. */
    private final AtomicLong generation = new AtomicLong();
    /** Beans found in a library jar, by jar path; kept across source changes until the jar itself changes. */
    private final Map<String, LibraryBeans> libraryBeans = new ConcurrentHashMap<>();

    /** @param stamp modification time and size of the jar the beans were found in */
    private record LibraryBeans(String stamp, List<IMember> members) {
    }

    /** @param sourcesDir where library sources are extracted for navigation, or null */
    JavaModelService(Path sourcesDir) {
        this.sourcesDir = sourcesDir;
    }

    void invalidate() {
        generation.incrementAndGet();
        typeCache.clear();
        beanCache.clear();
    }

    @Override
    public JsonElement project(JsonObject params) throws JavaModelException, QueryException {
        return describeProject(project(params, "project"));
    }

    @Override
    public JsonElement beans(JsonObject params) throws CoreException, QueryException {
        return beans(project(params, "beans"));
    }

    @Override
    public JsonElement type(JsonObject params) throws JavaModelException, QueryException {
        return typeWithSupertypes(project(params, "type"), params.get("fqn").getAsString());
    }

    @Override
    public JsonElement location(JsonObject params) throws JavaModelException, QueryException {
        return location(project(params, "location"), params);
    }

    @Override
    public JsonElement javadoc(JsonObject params) throws JavaModelException, QueryException {
        return javadoc(project(params, "javadoc"), params);
    }

    @Override
    public JsonElement findTypes(JsonObject params) throws JavaModelException, QueryException {
        return findTypes(project(params, "findTypes"), params.get("prefix").getAsString(),
                params.has("limit") ? params.get("limit").getAsInt() : 50);
    }

    @Override
    public JsonElement callHierarchyPrepare(JsonObject params) throws ReflectiveOperationException {
        return CallHierarchy.get().prepare(params);
    }

    @Override
    public JsonElement callHierarchyIncoming(JsonObject params) throws ReflectiveOperationException {
        return CallHierarchy.get().incoming(params);
    }

    @Override
    public JsonElement callHierarchyOutgoing(JsonObject params) throws ReflectiveOperationException {
        return CallHierarchy.get().outgoing(params);
    }

    @Override
    public JsonElement member(JsonObject params) throws JavaModelException {
        return CallHierarchy.memberAt(params);
    }

    /**
     * The project containing {@code params.path}. Fails when jdtls has no such project (yet): that is no answer about
     * the Java model, and the client must not take it for a type or member that does not exist.
     */
    private static IJavaProject project(JsonObject params, String method) throws JavaModelException, QueryException {
        String path = params.get("path").getAsString();
        IJavaProject project = findProject(path);
        if (project == null) {
            throw new QueryException(method + ": no Java project contains " + path);
        }
        return project;
    }

    private static IJavaProject[] javaProjects() throws JavaModelException {
        return JavaCore.create(ResourcesPlugin.getWorkspace().getRoot()).getJavaProjects();
    }

    @Override
    public JsonElement ping() throws JavaModelException {
        JsonObject result = new JsonObject();
        result.addProperty("pid", ProcessHandle.current().pid());
        JsonArray projects = new JsonArray();
        for (IJavaProject project : javaProjects()) {
            IPath location = project.getProject().getLocation();
            JsonObject p = new JsonObject();
            p.addProperty("name", project.getElementName());
            p.addProperty("location", location == null ? null : location.toOSString());
            projects.add(p);
        }
        result.add("projects", projects);
        return result;
    }

    /** The project with the longest location that contains {@code path}. */
    private static IJavaProject findProject(String path) throws JavaModelException {
        String normalized = new File(path).getAbsolutePath();
        IJavaProject best = null;
        int bestLength = -1;
        for (IJavaProject project : javaProjects()) {
            IPath location = project.getProject().getLocation();
            if (location == null) {
                continue;
            }
            String root = location.toOSString();
            boolean contains = normalized.equals(root) || normalized.startsWith(root + File.separator);
            if (contains && root.length() > bestLength) {
                best = project;
                bestLength = root.length();
            }
        }
        return best;
    }

    private static String absolute(IPath workspacePath) {
        IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(workspacePath);
        if (resource != null && resource.getLocation() != null) {
            return resource.getLocation().toOSString();
        }
        return workspacePath.toOSString();
    }

    private JsonElement describeProject(IJavaProject project) throws JavaModelException {
        JsonObject result = new JsonObject();
        result.addProperty("name", project.getElementName());
        result.addProperty("location", project.getProject().getLocation().toOSString());
        JsonArray sources = new JsonArray();
        JsonArray classpath = new JsonArray();
        for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
            if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                sources.add(absolute(root.getPath()));
            }
        }
        for (IClasspathEntry entry : project.getResolvedClasspath(true)) {
            switch (entry.getEntryKind()) {
                case IClasspathEntry.CPE_LIBRARY -> classpath.add(absolute(entry.getPath()));
                case IClasspathEntry.CPE_PROJECT -> {
                    IJavaProject referenced = JavaCore.create(ResourcesPlugin.getWorkspace().getRoot()
                            .getProject(entry.getPath().lastSegment()));
                    if (referenced.exists()) {
                        for (IPackageFragmentRoot root : referenced.getPackageFragmentRoots()) {
                            if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                                classpath.add(absolute(root.getPath()));
                            }
                        }
                    }
                }
                default -> {
                    // source folders are listed separately
                }
            }
        }
        result.add("sourceFolders", sources);
        result.add("classpath", classpath);
        return result;
    }

    /**
     * Named beans of the project, of the workspace projects it references (modules) and of its library jars.
     * Library results are cached per jar until the jar changes, so only sources are searched again after edits.
     */
    private JsonElement beans(IJavaProject project) throws CoreException {
        String key = project.getElementName();
        JsonElement cached = beanCache.get(key);
        if (cached != null) {
            return cached;
        }
        long started = generation.get();
        List<IMember> annotated = new ArrayList<>(searchNamed(SearchEngine.createJavaSearchScope(new IJavaElement[] {project},
                IJavaSearchScope.SOURCES | IJavaSearchScope.REFERENCED_PROJECTS)));

        List<IPackageFragmentRoot> pending = new ArrayList<>();
        for (IPackageFragmentRoot root : project.getAllPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_BINARY || isSystemLibrary(root)) {
                continue;
            }
            LibraryBeans known = libraryBeans.get(libraryPath(root));
            if (known != null && known.stamp().equals(libraryStamp(root))) {
                annotated.addAll(known.members());
            } else {
                pending.add(root);
            }
        }
        if (!pending.isEmpty()) {
            List<IMember> found = searchNamed(SearchEngine.createJavaSearchScope(pending.toArray(new IJavaElement[0])));
            for (IPackageFragmentRoot root : pending) {
                List<IMember> ofRoot = new ArrayList<>();
                for (IMember member : found) {
                    if (root.equals(member.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT))) {
                        ofRoot.add(member);
                    }
                }
                // replaces the entry of an older version of the same jar
                libraryBeans.put(libraryPath(root), new LibraryBeans(libraryStamp(root), List.copyOf(ofRoot)));
            }
            annotated.addAll(found);
        }

        Map<String, JsonObject> beans = new LinkedHashMap<>();
        for (IMember member : annotated) {
            if (isDuplicateBinary(member, annotated)) {
                continue;
            }
            JsonObject bean = describeBean(project, member);
            if (bean != null) {
                beans.putIfAbsent(bean.get("name").getAsString(), bean);
            }
        }
        JsonArray result = new JsonArray();
        beans.values().forEach(result::add);
        if (generation.get() == started) {
            beanCache.put(key, result);
        }
        return result;
    }

    /** Types, methods and fields annotated with one of {@link #NAMED_ANNOTATIONS}, in one search. */
    private static List<IMember> searchNamed(IJavaSearchScope scope) throws CoreException {
        SearchPattern pattern = null;
        for (String annotation : NAMED_ANNOTATIONS) {
            SearchPattern single = SearchPattern.createPattern(annotation, IJavaSearchConstants.ANNOTATION_TYPE,
                    IJavaSearchConstants.ANNOTATION_TYPE_REFERENCE, SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE);
            pattern = pattern == null ? single : SearchPattern.createOrPattern(pattern, single);
        }
        List<IMember> result = new ArrayList<>();
        new SearchEngine().search(pattern, new SearchParticipant[] {SearchEngine.getDefaultSearchParticipant()}, scope,
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) {
                        if (match.getElement() instanceof IMember member
                                && (member instanceof IType || member instanceof IMethod || member instanceof IField)
                                && !result.contains(member)) {
                            result.add(member);
                        }
                    }
                }, null);
        return result;
    }

    private static boolean isSystemLibrary(IPackageFragmentRoot root) throws JavaModelException {
        IClasspathEntry raw = root.getRawClasspathEntry();
        return raw != null && raw.getEntryKind() == IClasspathEntry.CPE_CONTAINER
                && raw.getPath().segment(0).equals("org.eclipse.jdt.launching.JRE_CONTAINER");
    }

    private static String libraryPath(IPackageFragmentRoot root) {
        return root.getPath().toOSString();
    }

    private static String libraryStamp(IPackageFragmentRoot root) {
        File file = root.getPath().toFile();
        return file.lastModified() + ":" + file.length();
    }

    /** A class found both as source (workspace module) and in a jar on the classpath: keep the source. */
    private static boolean isDuplicateBinary(IMember member, List<IMember> all) {
        if (!member.isBinary() || !(member instanceof IType type)) {
            return false;
        }
        String name = type.getFullyQualifiedName();
        return all.stream().anyMatch(other -> !other.isBinary() && other instanceof IType t && t.getFullyQualifiedName().equals(name));
    }

    private JsonObject describeBean(IJavaProject project, IMember member) throws JavaModelException {
        String explicitName = null;
        String kind = null;
        for (IAnnotation annotation : ((IAnnotatable) member).getAnnotations()) {
            String simple = simpleName(annotation.getElementName());
            if (simple.equals("Named") || simple.equals("ManagedBean") || simple.equals("Model")) {
                kind = simple;
                for (IMemberValuePair pair : annotation.getMemberValuePairs()) {
                    if ((pair.getMemberName().equals("value") || pair.getMemberName().equals("name"))
                            && pair.getValue() instanceof String value && !value.isEmpty()) {
                        explicitName = value;
                    }
                }
            }
        }
        if (kind == null) {
            return null;
        }

        String name;
        String type;
        if (member instanceof IType t) {
            name = explicitName != null ? explicitName : decapitalize(t.getElementName());
            type = t.getFullyQualifiedName('.');
        } else {
            IBinding binding = binding(project, member);
            if (member instanceof IMethod m) {
                name = explicitName != null ? explicitName : propertyName(m.getElementName());
                type = binding instanceof IMethodBinding mb ? mb.getReturnType().getQualifiedName() : "java.lang.Object";
            } else {
                name = explicitName != null ? explicitName : member.getElementName();
                type = binding instanceof IVariableBinding vb ? vb.getType().getQualifiedName() : "java.lang.Object";
            }
        }

        JsonObject bean = new JsonObject();
        bean.addProperty("name", name);
        bean.addProperty("type", type);
        bean.addProperty("kind", kind);
        IType declaring = member instanceof IType t ? t : member.getDeclaringType();
        for (IAnnotation annotation : declaring.getAnnotations()) {
            String simple = simpleName(annotation.getElementName());
            if (SCOPE_ANNOTATIONS.contains(simple)) {
                bean.addProperty("scope", simple.equals("Model") ? "RequestScoped" : simple);
            }
        }
        for (IAnnotation annotation : ((IAnnotatable) member).getAnnotations()) {
            String simple = simpleName(annotation.getElementName());
            if (SCOPE_ANNOTATIONS.contains(simple)) {
                bean.addProperty("scope", simple.equals("Model") ? "RequestScoped" : simple);
            }
        }
        JsonElement location = sourceLocation(member);
        bean.add("location", location);
        IPackageFragmentRoot root = (IPackageFragmentRoot) member.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
        if (member.isBinary() && root != null) {
            bean.addProperty("library", root.getElementName());
        } else if (!member.getJavaProject().equals(project)) {
            bean.addProperty("library", member.getJavaProject().getElementName());
        }
        return bean;
    }

    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    /** CDI's default bean name rule (first character to lower case), not the JavaBeans one of {@code Introspector}. */
    private static String decapitalize(String name) {
        if (name.isEmpty()) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static String propertyName(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return decapitalize(methodName.substring(3));
        }
        if (methodName.startsWith("is") && methodName.length() > 2) {
            return decapitalize(methodName.substring(2));
        }
        return methodName;
    }

    private static IBinding binding(IJavaProject project, IJavaElement element) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setProject(project);
        parser.setResolveBindings(true);
        IBinding[] bindings = parser.createBindings(new IJavaElement[] {element}, null);
        return bindings.length == 0 ? null : bindings[0];
    }

    private static IType findType(IJavaProject project, String fqn) throws JavaModelException {
        IType type = project.findType(fqn);
        if (type == null && fqn.contains(".")) {
            // nested type written with dots: try '$' from the right
            StringBuilder candidate = new StringBuilder(fqn);
            for (int i = candidate.length() - 1; i >= 0 && type == null; i--) {
                if (candidate.charAt(i) == '.') {
                    candidate.setCharAt(i, '$');
                    type = project.findType(candidate.toString());
                }
            }
        }
        return type;
    }

    /**
     * The description of a type followed by those of all its supertypes (each once, breadth-first), or
     * {@link JsonNull} when the type does not exist. Supertypes that cannot be resolved are left out.
     */
    private JsonElement typeWithSupertypes(IJavaProject project, String fqn) throws JavaModelException {
        TypeWalk walk = new TypeWalk(project, new IdentityHashMap<>(), generation.get());
        JsonElement root = type(walk, fqn, null);
        if (!(root instanceof JsonObject rootType)) {
            return JsonNull.INSTANCE;
        }
        JsonArray result = new JsonArray();
        Set<String> seen = new HashSet<>();
        Deque<JsonObject> queue = new ArrayDeque<>();
        queue.add(rootType);
        seen.add(rootType.get("fqn").getAsString());
        while (!queue.isEmpty()) {
            JsonObject current = queue.poll();
            result.add(current);
            ITypeBinding binding = walk.bindings().get(current);
            for (JsonElement supertype : current.getAsJsonArray("supertypes")) {
                String name = erasure(supertype.getAsString());
                if (seen.add(name) && type(walk, name, supertypeBinding(binding, name)) instanceof JsonObject described) {
                    queue.add(described);
                }
            }
        }
        return result;
    }

    private static String erasure(String type) {
        int angle = type.indexOf('<');
        return angle < 0 ? type : type.substring(0, angle);
    }

    /** The declaration binding of a direct supertype called {@code name}, so it need not be looked up again. */
    private static ITypeBinding supertypeBinding(ITypeBinding binding, String name) {
        if (binding == null) {
            return null;
        }
        List<ITypeBinding> supertypes = new ArrayList<>(List.of(binding.getInterfaces()));
        if (binding.getSuperclass() != null) {
            supertypes.add(0, binding.getSuperclass());
        }
        for (ITypeBinding supertype : supertypes) {
            ITypeBinding declaration = supertype.getTypeDeclaration();
            if (declaration.getErasure().getQualifiedName().equals(name) && !declaration.isRecovered()) {
                return declaration;
            }
        }
        return null;
    }

    /**
     * One {@link #typeWithSupertypes} call: the bindings of the descriptions it made, so their supertypes need no
     * lookup, and the cache generation it started in.
     */
    private record TypeWalk(IJavaProject project, Map<JsonObject, ITypeBinding> bindings, long generation) {
    }

    /** The cached description of a type, made from {@code binding} when given or else from the type's name. */
    private JsonElement type(TypeWalk walk, String fqn, ITypeBinding binding) throws JavaModelException {
        IJavaProject project = walk.project();
        String key = project.getElementName() + '|' + fqn;
        JsonElement cached = typeCache.get(key);
        if (cached != null) {
            return cached;
        }
        if (binding == null) {
            IType type = findType(project, fqn);
            binding = type != null && binding(project, type) instanceof ITypeBinding tb ? tb : null;
        }
        JsonElement result = binding == null ? JsonNull.INSTANCE : describeType(binding);
        if (result instanceof JsonObject described) {
            walk.bindings().put(described, binding);
        }
        if (generation.get() == walk.generation()) {
            typeCache.put(key, result);
        }
        return result;
    }

    private static JsonObject describeType(ITypeBinding tb) {
        JsonObject result = new JsonObject();
        result.addProperty("fqn", tb.getErasure().getQualifiedName());
        result.addProperty("kind", tb.isInterface() ? (tb.isAnnotation() ? "annotation" : "interface")
                : tb.isEnum() ? "enum" : tb.isRecord() ? "record" : "class");
        result.addProperty("source", tb.getJavaElement() instanceof IType type && !type.isBinary());
        result.addProperty("deprecated", tb.isDeprecated());

        JsonArray typeParameters = new JsonArray();
        for (ITypeBinding parameter : tb.getTypeParameters()) {
            typeParameters.add(parameter.getName());
        }
        result.add("typeParameters", typeParameters);

        JsonArray supertypes = new JsonArray();
        if (tb.getSuperclass() != null) {
            supertypes.add(tb.getSuperclass().getQualifiedName());
        }
        for (ITypeBinding superInterface : tb.getInterfaces()) {
            supertypes.add(superInterface.getQualifiedName());
        }
        if (tb.isInterface() && supertypes.isEmpty()) {
            supertypes.add("java.lang.Object");
        }
        result.add("supertypes", supertypes);

        JsonArray methods = new JsonArray();
        for (IMethodBinding method : tb.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            boolean visible = Modifier.isPublic(modifiers) || (tb.isInterface() && !Modifier.isPrivate(modifiers));
            if (!visible || method.isConstructor() || method.isSynthetic() || method.isDefaultConstructor()) {
                continue;
            }
            JsonObject m = new JsonObject();
            m.addProperty("name", method.getName());
            JsonArray parameters = new JsonArray();
            for (ITypeBinding parameter : method.getParameterTypes()) {
                parameters.add(parameter.getQualifiedName());
            }
            m.add("params", parameters);
            String[] parameterNames = method.getParameterNames();
            if (parameterNames.length == parameters.size()) {
                JsonArray names = new JsonArray();
                for (String parameterName : parameterNames) {
                    names.add(parameterName);
                }
                m.add("paramNames", names);
            }
            m.addProperty("returnType", method.getReturnType().getQualifiedName());
            m.addProperty("static", Modifier.isStatic(modifiers));
            m.addProperty("varargs", method.isVarargs());
            m.addProperty("deprecated", method.isDeprecated());
            methods.add(m);
        }
        result.add("methods", methods);

        JsonArray fields = new JsonArray();
        for (IVariableBinding field : tb.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            boolean visible = Modifier.isPublic(modifiers) || tb.isInterface();
            if (!visible || field.isSynthetic()) {
                continue;
            }
            JsonObject f = new JsonObject();
            f.addProperty("name", field.getName());
            f.addProperty("type", field.getType().getQualifiedName());
            f.addProperty("static", Modifier.isStatic(modifiers) || tb.isInterface());
            f.addProperty("enumConstant", field.isEnumConstant());
            fields.add(f);
        }
        result.add("fields", fields);

        if (tb.isRecord()) {
            JsonArray components = new JsonArray();
            for (IVariableBinding field : tb.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    components.add(field.getName());
                }
            }
            result.add("recordComponents", components);
        }
        return result;
    }

    /** Resolves the member described by {@code fqn}, {@code kind} (type, method, field), {@code name} and {@code params}. */
    private static IMember member(IJavaProject project, JsonObject params) throws JavaModelException {
        IType type = findType(project, params.get("fqn").getAsString());
        if (type == null) {
            return null;
        }
        String kind = params.has("kind") ? params.get("kind").getAsString() : "type";
        String name = params.has("name") ? params.get("name").getAsString() : null;
        int parameterCount = params.has("paramCount") ? params.get("paramCount").getAsInt() : -1;
        switch (kind) {
            case "method" -> {
                IMethod fallback = null;
                for (IMethod method : type.getMethods()) {
                    if (!method.getElementName().equals(name) || method.isConstructor()) {
                        continue;
                    }
                    if (parameterCount < 0 || method.getNumberOfParameters() == parameterCount) {
                        return method;
                    }
                    if (fallback == null || Flags.isVarargs(method.getFlags())) {
                        fallback = method;
                    }
                }
                return fallback;
            }
            case "field" -> {
                IField field = type.getField(name);
                return field.exists() ? field : null;
            }
            default -> {
                return type;
            }
        }
    }

    private JsonElement location(IJavaProject project, JsonObject params) throws JavaModelException {
        IMember member = member(project, params);
        return member == null ? JsonNull.INSTANCE : sourceLocation(member);
    }

    /**
     * The file and name range of a member. Members of library classes with attached sources are located in a
     * read-only copy of the source extracted below {@link #sourcesDir}, since editors cannot open files inside jars.
     */
    private JsonElement sourceLocation(IMember member) throws JavaModelException {
        String uri;
        String source;
        if (!member.isBinary() && member.getCompilationUnit() != null) {
            IResource resource = member.getCompilationUnit().getResource();
            if (resource == null || resource.getLocation() == null) {
                return JsonNull.INSTANCE;
            }
            uri = resource.getLocation().toFile().toURI().toString();
            source = member.getCompilationUnit().getSource();
        } else {
            Path extracted = extractSource(member);
            if (extracted == null) {
                return JsonNull.INSTANCE;
            }
            uri = extracted.toUri().toString();
            source = member.getTypeRoot().getSource();
        }
        ISourceRange range = member.getNameRange();
        JsonObject location = new JsonObject();
        location.addProperty("uri", uri);
        JsonObject lspRange = new JsonObject();
        int offset = range == null || range.getOffset() < 0 ? 0 : range.getOffset();
        int length = range == null || range.getLength() < 0 ? 0 : range.getLength();
        lspRange.add("start", SourcePositions.position(source, offset));
        lspRange.add("end", SourcePositions.position(source, offset + length));
        location.add("range", lspRange);
        return location;
    }

    private Path extractSource(IMember member) throws JavaModelException {
        if (sourcesDir == null || !(member.getTypeRoot() instanceof IClassFile classFile)) {
            return null;
        }
        String source = classFile.getSource();
        if (source == null) {
            return null;
        }
        IType type = member instanceof IType t ? t : member.getDeclaringType();
        while (type.getDeclaringType() != null) {
            type = type.getDeclaringType();
        }
        IPackageFragmentRoot root = (IPackageFragmentRoot) member.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
        String rootName = root == null ? "library" : root.getElementName().replaceAll("[^A-Za-z0-9._-]", "_");
        Path file = sourcesDir.resolve(rootName).resolve(type.getFullyQualifiedName().replace('.', '/') + ".java");
        try {
            if (!Files.isRegularFile(file) || !Files.readString(file, StandardCharsets.UTF_8).equals(source)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, source, StandardCharsets.UTF_8);
                file.toFile().setReadOnly();
            }
        } catch (IOException e) {
            return null;
        }
        return file;
    }

    private JsonElement javadoc(IJavaProject project, JsonObject params) throws JavaModelException {
        IMember member = member(project, params);
        String markdown = member == null ? null : Javadoc.markdown(member);
        if (markdown == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject result = new JsonObject();
        result.addProperty("markdown", markdown);
        return result;
    }

    private JsonElement findTypes(IJavaProject project, String prefix, int limit) throws JavaModelException {
        JsonArray result = new JsonArray();
        if (prefix.isEmpty()) {
            return result;
        }
        int dot = prefix.lastIndexOf('.');
        char[] packageName = dot < 0 ? null : prefix.substring(0, dot).toCharArray();
        char[] typeName = (dot < 0 ? prefix : prefix.substring(dot + 1)).toCharArray();
        IJavaSearchScope scope = SearchEngine.createJavaSearchScope(new IJavaElement[] {project});
        new SearchEngine().searchAllTypeNames(packageName, SearchPattern.R_PREFIX_MATCH, typeName,
                SearchPattern.R_PREFIX_MATCH | SearchPattern.R_CAMELCASE_MATCH, IJavaSearchConstants.TYPE, scope,
                new TypeNameMatchRequestor() {
                    @Override
                    public void acceptTypeNameMatch(TypeNameMatch match) {
                        if (result.size() >= limit || !Flags.isPublic(match.getModifiers())) {
                            return;
                        }
                        JsonObject type = new JsonObject();
                        type.addProperty("fqn", match.getFullyQualifiedName().replace('$', '.'));
                        type.addProperty("simpleName", match.getSimpleTypeName());
                        result.add(type);
                    }
                }, IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH, null);
        return result;
    }
}
