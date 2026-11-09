package io.github.jungm.fls.jdt;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.eclipse.jdt.core.ITypeRoot;
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
final class JavaModelService {
    private static final List<String> NAMED_ANNOTATIONS = List.of(
            "jakarta.inject.Named", "javax.inject.Named",
            "jakarta.enterprise.inject.Model", "javax.enterprise.inject.Model",
            "jakarta.faces.bean.ManagedBean", "javax.faces.bean.ManagedBean");

    private static final Set<String> SCOPE_ANNOTATIONS = Set.of(
            "RequestScoped", "SessionScoped", "ApplicationScoped", "ConversationScoped", "Dependent",
            "ViewScoped", "FlowScoped", "NoneScoped", "CustomScoped", "Model", "ClientWindowScoped");

    private final Map<String, JsonElement> typeCache = new ConcurrentHashMap<>();
    private final Path sourcesDir;
    private final Map<String, JsonElement> beanCache = new ConcurrentHashMap<>();
    /** Beans found in a library jar, by jar path and timestamp; kept across source changes. */
    private final Map<String, List<IMember>> libraryBeans = new ConcurrentHashMap<>();

    /** @param sourcesDir where library sources are extracted for navigation, or null */
    JavaModelService(Path sourcesDir) {
        this.sourcesDir = sourcesDir;
    }

    void invalidate() {
        typeCache.clear();
        beanCache.clear();
    }

    JsonElement handle(String method, JsonObject params) throws CoreException {
        return switch (method) {
            case "ping" -> ping();
            case "project" -> describeProject(findProject(params.get("path").getAsString()));
            case "beans" -> withProject(params, this::beans);
            case "type" -> withProject(params, project -> type(project, params.get("fqn").getAsString()));
            case "location" -> withProject(params, project -> location(project, params));
            case "javadoc" -> withProject(params, project -> javadoc(project, params));
            case "findTypes" -> withProject(params, project -> findTypes(project, params.get("prefix").getAsString(),
                    params.has("limit") ? params.get("limit").getAsInt() : 50));
            default -> throw new IllegalArgumentException("unknown method " + method);
        };
    }

    private interface ProjectFunction {
        JsonElement apply(IJavaProject project) throws CoreException;
    }

    private JsonElement withProject(JsonObject params, ProjectFunction function) throws CoreException {
        IJavaProject project = findProject(params.get("path").getAsString());
        return project == null ? JsonNull.INSTANCE : function.apply(project);
    }

    private static IJavaProject[] javaProjects() throws JavaModelException {
        return JavaCore.create(ResourcesPlugin.getWorkspace().getRoot()).getJavaProjects();
    }

    private JsonElement ping() throws JavaModelException {
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
        if (project == null) {
            return JsonNull.INSTANCE;
        }
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
        List<IMember> annotated = new ArrayList<>(searchNamed(SearchEngine.createJavaSearchScope(new IJavaElement[] {project},
                IJavaSearchScope.SOURCES | IJavaSearchScope.REFERENCED_PROJECTS)));

        List<IPackageFragmentRoot> pending = new ArrayList<>();
        for (IPackageFragmentRoot root : project.getAllPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_BINARY || isSystemLibrary(root)) {
                continue;
            }
            List<IMember> known = libraryBeans.get(libraryKey(root));
            if (known != null) {
                annotated.addAll(known);
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
                libraryBeans.put(libraryKey(root), List.copyOf(ofRoot));
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
        beanCache.put(key, result);
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

    private static String libraryKey(IPackageFragmentRoot root) {
        File file = root.getPath().toFile();
        return root.getPath().toOSString() + ":" + file.lastModified() + ":" + file.length();
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

    private JsonElement type(IJavaProject project, String fqn) throws JavaModelException {
        String key = project.getElementName() + '|' + fqn;
        JsonElement cached = typeCache.get(key);
        if (cached != null) {
            return cached;
        }
        IType type = findType(project, fqn);
        JsonElement result = type == null ? JsonNull.INSTANCE : describeType(project, type);
        typeCache.put(key, result);
        return result;
    }

    private JsonElement describeType(IJavaProject project, IType type) throws JavaModelException {
        IBinding binding = binding(project, type);
        if (!(binding instanceof ITypeBinding tb)) {
            return JsonNull.INSTANCE;
        }
        JsonObject result = new JsonObject();
        result.addProperty("fqn", tb.getErasure().getQualifiedName());
        result.addProperty("kind", tb.isInterface() ? (tb.isAnnotation() ? "annotation" : "interface")
                : tb.isEnum() ? "enum" : tb.isRecord() ? "record" : "class");
        result.addProperty("source", !type.isBinary());
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
        lspRange.add("start", position(source, offset));
        lspRange.add("end", position(source, offset + length));
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

    private static JsonObject position(String source, int offset) {
        int line = 0;
        int lineStart = 0;
        int limit = Math.min(offset, source == null ? 0 : source.length());
        for (int i = 0; i < limit; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        JsonObject position = new JsonObject();
        position.addProperty("line", line);
        position.addProperty("character", limit - lineStart);
        return position;
    }

    private static final Pattern INLINE_TAG = Pattern.compile("\\{@(?:code|literal)\\s+([^}]*)}|\\{@(?:link|linkplain|value)\\s+([^}\\s]*)[^}]*}");

    private JsonElement javadoc(IJavaProject project, JsonObject params) throws JavaModelException {
        IMember member = member(project, params);
        if (member == null) {
            return JsonNull.INSTANCE;
        }
        ISourceRange range = member.getJavadocRange();
        ITypeRoot root = member.getTypeRoot();
        String source = root == null ? null : root.getSource();
        if (range == null || source == null) {
            return JsonNull.INSTANCE;
        }
        String raw = source.substring(range.getOffset(), range.getOffset() + range.getLength());
        JsonObject result = new JsonObject();
        result.addProperty("markdown", javadocToMarkdown(raw));
        return result;
    }

    static String javadocToMarkdown(String raw) {
        String body = raw.replaceFirst("^/\\*\\*", "").replaceFirst("\\*/$", "");
        StringBuilder text = new StringBuilder();
        for (String line : body.split("\\R")) {
            text.append(line.replaceFirst("^\\s*\\*\\s?", "")).append('\n');
        }
        String result = text.toString().trim();
        Matcher matcher = INLINE_TAG.matcher(result);
        StringBuilder replaced = new StringBuilder();
        while (matcher.find()) {
            String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2).replace('#', '.');
            matcher.appendReplacement(replaced, Matcher.quoteReplacement("`" + value.trim() + "`"));
        }
        matcher.appendTail(replaced);
        result = replaced.toString()
                .replaceAll("(?i)<p>\\s*", "\n\n")
                .replaceAll("(?i)</?(b|strong)>", "**")
                .replaceAll("(?i)</?(i|em)>", "_")
                .replaceAll("(?i)</?code>", "`")
                .replaceAll("(?i)<li>", "\n- ")
                .replaceAll("<[^>]+>", "")
                .replaceAll("(?m)^@(param|return|throws|exception|see|since|deprecated)\\b", "\n**@$1**");
        return result.trim();
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
        try {
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
        } catch (JavaModelException e) {
            throw e;
        }
        return result;
    }
}
