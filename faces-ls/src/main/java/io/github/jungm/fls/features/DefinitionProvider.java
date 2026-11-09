package io.github.jungm.fls.features;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.ElType;
import io.github.jungm.fls.analysis.ElVariable;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.analysis.FacesDocument.ElSite;
import io.github.jungm.fls.analysis.Imports;
import io.github.jungm.fls.analysis.LineIndex;
import io.github.jungm.fls.index.FaceletsIndex;
import io.github.jungm.fls.java.TypeService;
import io.github.jungm.fls.project.PropertiesFile;
import io.github.jungm.fls.taglib.Namespaces;
import io.github.jungm.fls.taglib.TagLibrary;
import io.github.jungm.fls.taglib.TaglibDescriptor;
import io.github.jungm.fls.xml.XmlAttribute;
import io.github.jungm.fls.xml.XmlDocument;
import io.github.jungm.fls.xml.XmlElement;

/**
 * Go to definition: EL names to Java sources, bundles and declarations; tags to composite component files;
 * paths and ids to their targets.
 */
public final class DefinitionProvider {
    private final ElAnalyzer analyzer;
    private final FacesDocument document;
    private final Function<String, String> openDocumentText;

    /** @param openDocumentText text of an open document by URI (unsaved edits), or null when not open */
    public DefinitionProvider(ElAnalyzer analyzer, Function<String, String> openDocumentText) {
        this.analyzer = analyzer;
        this.document = analyzer.document();
        this.openDocumentText = openDocumentText;
    }

    public List<Location> definition(int offset) {
        List<Location> result = new ArrayList<>();
        ElSite site = document.elAt(offset);
        if (site != null) {
            Location location = elDefinition(site, offset);
            if (location != null) {
                result.add(location);
            }
            return result;
        }
        Cursor cursor = Cursor.on(document, offset);
        if (cursor.kind() == Cursor.Kind.ATTRIBUTE_VALUE
                && SearchExpressions.isSearchAttribute(cursor.element(), cursor.attribute().name())) {
            result.addAll(searchExpressionTargets(cursor.element(), cursor.attribute(), offset));
            return result;
        }
        if (cursor.kind() == Cursor.Kind.ATTRIBUTE_VALUE && cursor.attribute().name().equals("name")
                && isFacelets(cursor.element(), "define") && cursor.attribute().value() != null) {
            result.addAll(slots(cursor.element(), cursor.attribute().value().trim()));
            return result;
        }
        Location location = switch (cursor.kind()) {
            case TAG_NAME, CLOSE_TAG_NAME -> tagDefinition(cursor.element());
            case ATTRIBUTE_NAME -> attributeDefinition(cursor.element(), cursor.attribute());
            case ATTRIBUTE_VALUE -> valueDefinition(cursor.element(), cursor.attribute());
            default -> null;
        };
        if (location != null) {
            result.add(location);
        }
        return result;
    }

    // ---------------------------------------------------------------- EL

    private Location elDefinition(ElSite site, int offset) {
        ElLookup.Hit hit = ElLookup.find(analyzer, site, offset);
        if (hit == null) {
            return null;
        }
        switch (hit.kind()) {
            case VARIABLE -> {
                ElVariable variable = hit.variable();
                if (variable == null) {
                    return null;
                }
                if (variable.javaLocation() != null) {
                    return java(variable.javaLocation());
                }
                if (variable.declarationUri() != null && variable.declarationOffset() >= 0) {
                    return atOffset(variable.declarationUri(), variable.declarationOffset(), variable.name().length());
                }
                if (variable.type() instanceof ElType.Java java) {
                    return java(analyzer.types().location(java.type().name(), "type", null, -1));
                }
                return null;
            }
            case MEMBER, KEY -> {
                ElLookup.MemberTarget target = ElLookup.resolveMember(analyzer, hit.base(), hit.name(), hit.arguments());
                return target == null ? null : memberLocation(target, hit.name());
            }
            case FUNCTION -> {
                String namespace = site.element() == null ? null : site.element().namespaceFor(hit.function().prefix());
                Imports.Import imported = namespace == null ? analyzer.functionImport(hit.function().prefix()) : null;
                if (imported != null) {
                    TypeService.MethodInfo method = ElAnalyzer.select(analyzer.importedFunctions(imported).stream()
                            .filter(m -> m.name().equals(hit.name())).toList(), hit.arguments());
                    return method == null ? null : java(analyzer.types().location(method.declaringType(), "method",
                            method.name(), method.parameters().size()));
                }
                TagLibrary library = namespace == null ? null : analyzer.project().taglibs().library(namespace);
                TagLibrary.FunctionDef function = library == null ? null : library.functions().get(hit.name());
                if (function == null || function.functionClass() == null) {
                    return null;
                }
                String method = function.signature() == null ? hit.name()
                        : function.signature().replaceFirst("\\s*\\(.*", "").replaceFirst(".*\\s", "");
                return java(analyzer.types().location(function.functionClass(), "method", method,
                        function.parameterTypes().size()));
            }
            default -> {
                return null;
            }
        }
    }

    private Location memberLocation(ElLookup.MemberTarget target, String name) {
        TypeService types = analyzer.types();
        if (target.property() != null) {
            TypeService.MethodInfo accessor = target.property().getter() != null ? target.property().getter() : target.property().setter();
            return java(types.location(accessor.declaringType(), "method", accessor.name(), accessor.parameters().size()));
        }
        if (target.method() != null) {
            return java(types.location(target.method().declaringType(), "method", target.method().name(),
                    target.method().parameters().size()));
        }
        if (target.field() != null) {
            return java(types.location(target.field().declaringType(), "field", target.field().name(), -1));
        }
        if (target.compositeAttribute() != null && target.compositeContract() != null
                && target.compositeContract().fileUri() != null && target.compositeAttribute().offset() >= 0) {
            return atOffset(target.compositeContract().fileUri(), target.compositeAttribute().offset(), name.length());
        }
        if (target.bundleBaseName() != null) {
            PropertiesFile file = analyzer.project().bundle(target.bundleBaseName());
            if (file == null) {
                return null;
            }
            PropertiesFile.Entry entry = file.entries().get(name);
            int line = entry == null ? 0 : entry.line();
            int length = entry == null ? 0 : name.length();
            return new Location(file.path().toUri().toString(), new Range(new Position(line, 0), new Position(line, length)));
        }
        return null;
    }

    private static Location java(TypeService.SourceLocation location) {
        if (location == null) {
            return null;
        }
        return new Location(location.uri(), new Range(new Position(location.startLine(), location.startCharacter()),
                new Position(location.endLine(), location.endCharacter())));
    }

    private Location atOffset(String uri, int offset, int length) {
        String text = uri.equals(document.uri()) ? document.text() : textOf(uri);
        if (text == null) {
            return null;
        }
        LineIndex lines = new LineIndex(text);
        return new Location(uri, lines.range(offset, Math.min(text.length(), offset + length)));
    }

    private String textOf(String uri) {
        String open = openDocumentText.apply(uri);
        if (open != null) {
            return open;
        }
        try {
            return Files.readString(Path.of(URI.create(uri)));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Location fileStart(Path file) {
        return new Location(file.toUri().toString(), new Range(new Position(0, 0), new Position(0, 0)));
    }

    // ---------------------------------------------------------------- markup

    /** The composite component file, or else the tag's entry in its taglib descriptor. */
    private Location tagDefinition(XmlElement element) {
        Tags.Resolved resolved = Tags.resolve(analyzer.project(), element);
        if (resolved.tag() == null) {
            return null;
        }
        if (resolved.tag().fileUri() != null) {
            return new Location(resolved.tag().fileUri(), new Range(new Position(0, 0), new Position(0, 0)));
        }
        return descriptorLocation(resolved.library(), element.localName(), null);
    }

    /** The {@code cc:attribute} of a composite component, or else the attribute in the taglib descriptor. */
    private Location attributeDefinition(XmlElement element, XmlAttribute attribute) {
        Tags.Resolved resolved = Tags.resolve(analyzer.project(), element);
        if (resolved.tag() == null) {
            return null;
        }
        TagLibrary.AttributeDef definition = resolved.tag().attribute(attribute.name());
        if (resolved.tag().fileUri() != null) {
            if (definition == null || definition.offset() < 0) {
                return null;
            }
            return atOffset(resolved.tag().fileUri(), definition.offset(), definition.name().length());
        }
        return descriptorLocation(resolved.library(), element.localName(), definition == null ? null : definition.name());
    }

    private static Location descriptorLocation(TagLibrary library, String tagName, String attributeName) {
        if (library == null || library.descriptor() == null) {
            return null;
        }
        TaglibDescriptor.Position position = library.descriptor().find(tagName, attributeName);
        if (position == null) {
            return null;
        }
        LineIndex lines = new LineIndex(position.text());
        return new Location(position.file().toUri().toString(),
                lines.range(position.offset(), position.offset() + position.length()));
    }

    private Location valueDefinition(XmlElement element, XmlAttribute attribute) {
        String value = attribute.value() == null ? "" : attribute.value().trim();
        if (value.isEmpty()) {
            return null;
        }
        String namespace = Namespaces.canonical(element.namespace());
        String local = element.localName();
        String name = attribute.name();
        if (Namespaces.FACELETS.equals(namespace) && ((local.equals("include") && name.equals("src"))
                || ((local.equals("composition") || local.equals("decorate")) && name.equals("template")))) {
            Path target = resolveView(value);
            return target == null ? null : fileStart(target);
        }
        if (name.equals("type") && (Namespaces.COMPOSITE.equals(namespace) && local.equals("attribute")
                || analyzer.imports().isTypeAttribute(element, name))) {
            return java(analyzer.types().location(value.replace('$', '.'), "type", null, -1));
        }
        if (SearchExpressions.isSearchAttribute(element, name)) {
            return null;
        }
        if (name.startsWith("xmlns:")) {
            String library = Namespaces.compositeLibrary(value);
            if (library != null) {
                for (Path webRoot : analyzer.project().webRoots()) {
                    Path dir = webRoot.resolve("resources").resolve(library);
                    if (Files.isDirectory(dir)) {
                        return fileStart(dir);
                    }
                }
            }
        }
        return null;
    }

    /** The components a search expression resolves to, up to the segment under the cursor. */
    private List<Location> searchExpressionTargets(XmlElement element, XmlAttribute attribute, int offset) {
        List<Location> locations = new ArrayList<>();
        SearchExpressions search = new SearchExpressions(analyzer.project(), document);
        SearchExpressions.Hit hit = SearchExpressions.at(SearchExpressions.parse(attribute.value(), attribute.valueStart()), offset);
        if (hit == null) {
            return locations;
        }
        if (hit.expression().absolute()) {
            String clientId = ClientIdLookup.clientId(hit.expression(), hit.segment());
            List<io.github.jungm.fls.index.ClientIds.ClientId> found = clientId == null ? List.of() : ClientIdLookup.find(analyzer, clientId);
            if (!found.isEmpty()) {
                for (io.github.jungm.fls.index.ClientIds.ClientId target : found) {
                    io.github.jungm.fls.index.FaceletsIndex.Entry entry = analyzer.index().entry(target.fileUri());
                    locations.add(new Location(target.fileUri(), entry.lines().range(target.start(), target.end())));
                }
                return locations;
            }
        }
        for (XmlElement target : search.resolve(element, hit.expression(), hit.segment())) {
            XmlAttribute id = SearchExpressions.idAttribute(target);
            locations.add(id != null && id.hasValue()
                    ? new Location(document.uri(), document.lines().range(id.valueStart(), id.valueEnd()))
                    : new Location(document.uri(), document.lines().range(target.nameStart(), target.nameEnd())));
        }
        return locations;
    }

    /**
     * Resolves a Facelets path: relative paths against the current file, absolute ones against its web root, and
     * else against the web roots of other modules and {@code META-INF/resources} of library jars.
     */
    private Path resolveView(String value) {
        return resolveView(document.path(), document.uri(), value);
    }

    /** Resolves a Facelets path that appears in {@code from}, which may be a file other than the current one. */
    private Path resolveView(Path from, String fromUri, String value) {
        Path local = value.startsWith("/")
                ? analyzer.project().webRootFor(from).resolve(value.substring(1)).normalize()
                : from.getParent().resolve(value).normalize();
        if (Files.isRegularFile(local)) {
            return local;
        }
        FaceletsIndex.Snapshot index = analyzer.index();
        if (index != null) {
            for (String uri : index.resolve(fromUri, value)) {
                try {
                    Path file = Path.of(URI.create(uri));
                    if (Files.isRegularFile(file)) {
                        return file;
                    }
                } catch (RuntimeException e) {
                    // not a file URI
                }
            }
        }
        String webPath = webPath(from, fromUri, value);
        return webPath == null ? null
                : analyzer.project().findView(webPath, analyzer.project().webRootFor(from)).orElse(null);
    }

    /** The path below the web root that {@code value} denotes in {@code from}, or null. */
    private String webPath(Path from, String fromUri, String value) {
        if (value.startsWith("/")) {
            return value;
        }
        FaceletsIndex.Snapshot index = analyzer.index();
        FaceletsIndex.Entry entry = index == null ? null : index.entry(fromUri);
        String current;
        if (entry != null && entry.webPath() != null) {
            current = entry.webPath();
        } else {
            Path webRoot = analyzer.project().webRootFor(from);
            if (!from.startsWith(webRoot)) {
                return null;
            }
            current = "/" + webRoot.relativize(from).toString().replace('\\', '/');
        }
        String resolved = Path.of(current).getParent().resolve(value).normalize().toString().replace('\\', '/');
        return resolved.startsWith("/") ? resolved : null;
    }

    // ---------------------------------------------------------------- template slots

    private static boolean isFacelets(XmlElement element, String localName) {
        return element.localName().equals(localName) && Namespaces.FACELETS.equals(Namespaces.canonical(element.namespace()));
    }

    /** A literal view path in an attribute, or null when it is missing or an expression. */
    private static String literalPath(XmlElement element, String attributeName) {
        String value = element.attributeValue(attributeName);
        if (value == null || value.isBlank() || value.contains("#{") || value.contains("${")) {
            return null;
        }
        return value.trim();
    }

    /**
     * The {@code ui:insert}s a {@code ui:define} fills: in the template of the enclosing {@code ui:composition} or
     * {@code ui:decorate}, in the files that template includes, and up its own template chain for names it does not
     * define itself. Falls back to the start of the template when no slot has that name.
     */
    private List<Location> slots(XmlElement define, String name) {
        List<Location> result = new ArrayList<>();
        for (XmlElement client = define.parent(); client != null; client = client.parent()) {
            if (!(isFacelets(client, "composition") || isFacelets(client, "decorate"))) {
                continue;
            }
            String template = literalPath(client, "template");
            if (template == null) {
                continue;
            }
            Path file = resolveView(template);
            if (file != null) {
                collectSlots(file, name, new HashSet<>(), result);
                if (result.isEmpty()) {
                    result.add(fileStart(file));
                }
            }
            return result;
        }
        return result;
    }

    private void collectSlots(Path file, String name, Set<Path> visited, List<Location> result) {
        if (!visited.add(file) || visited.size() > 40) {
            return;
        }
        String uri = file.toUri().toString();
        String text = textOf(uri);
        if (text == null) {
            return;
        }
        XmlDocument xml = XmlDocument.parse(text);
        LineIndex lines = new LineIndex(text);
        // Facelets drops everything around a ui:composition
        XmlElement composition = null;
        for (XmlElement element : xml.allElements()) {
            if (isFacelets(element, "composition")) {
                composition = element;
                break;
            }
        }
        for (XmlElement root : composition != null ? List.of(composition) : xml.roots()) {
            walkSlots(file, uri, lines, root, name, visited, result);
        }
    }

    private void walkSlots(Path file, String uri, LineIndex lines, XmlElement element, String name, Set<Path> visited,
                           List<Location> result) {
        if (isFacelets(element, "insert")) {
            XmlAttribute insertName = element.attribute("name");
            if (insertName != null && insertName.value() != null && name.equals(insertName.value().trim())) {
                result.add(new Location(uri, lines.range(insertName.valueStart(), insertName.valueEnd())));
            }
        } else if (isFacelets(element, "include")) {
            String src = literalPath(element, "src");
            Path included = src == null ? null : resolveView(file, uri, src);
            if (included != null) {
                collectSlots(included, name, visited, result);
            }
        } else if ((isFacelets(element, "composition") || isFacelets(element, "decorate"))
                && literalPath(element, "template") != null) {
            // a template that is itself a template client: only its ui:defines are rendered, and the names it
            // does not define pass through to its own template
            List<XmlElement> defines = new ArrayList<>();
            collectDefines(element, defines);
            boolean definesName = defines.stream().anyMatch(d -> name.equals(trimmed(d.attributeValue("name"))));
            if (!definesName) {
                Path parent = resolveView(file, uri, literalPath(element, "template"));
                if (parent != null) {
                    collectSlots(parent, name, visited, result);
                }
            }
            for (XmlElement define : defines) {
                for (XmlElement child : define.children()) {
                    walkSlots(file, uri, lines, child, name, visited, result);
                }
            }
            return;
        }
        for (XmlElement child : element.children()) {
            walkSlots(file, uri, lines, child, name, visited, result);
        }
    }

    private static void collectDefines(XmlElement element, List<XmlElement> result) {
        for (XmlElement child : element.children()) {
            if (isFacelets(child, "define")) {
                result.add(child);
            } else if (!isFacelets(child, "composition") && !isFacelets(child, "decorate")) {
                collectDefines(child, result);
            }
        }
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }
}
