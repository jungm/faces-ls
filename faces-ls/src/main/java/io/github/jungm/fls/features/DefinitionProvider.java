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
import io.github.jungm.fls.analysis.FaceletsTags;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.analysis.FacesDocument.ElSite;
import io.github.jungm.fls.analysis.Imports;
import io.github.jungm.fls.analysis.LineIndex;
import io.github.jungm.fls.el.ElRegion;
import io.github.jungm.fls.index.ClientIds.ClientId;
import io.github.jungm.fls.index.FaceletsIndex;
import io.github.jungm.fls.index.ViewPaths;
import io.github.jungm.fls.java.TypeService;
import io.github.jungm.fls.project.PropertiesFile;
import io.github.jungm.fls.project.Resources;
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
            ElLookup.Hit hit = ElLookup.find(analyzer, site, offset);
            if (isResourceKey(hit)) {
                result.addAll(resourceKey(hit.name()));
                return result;
            }
            Location location = elDefinition(site, hit);
            if (location != null) {
                result.add(location);
            }
            return result;
        }
        Cursor cursor = Cursor.on(document, offset);
        if (cursor.kind() == Cursor.Kind.ATTRIBUTE_VALUE) {
            List<Location> resources = resourceAttribute(cursor.element(), cursor.attribute(), offset);
            if (resources != null) {
                result.addAll(resources);
                return result;
            }
        }
        if (cursor.kind() == Cursor.Kind.ATTRIBUTE_VALUE
                && SearchExpressions.isSearchAttribute(cursor.element(),
                        PassthroughElements.componentAttributeName(cursor.element(), cursor.attribute()))) {
            result.addAll(searchExpressionTargets(cursor.element(), cursor.attribute(), offset));
            return result;
        }
        if (cursor.kind() == Cursor.Kind.ATTRIBUTE_VALUE && cursor.attribute().name().equals("name")
                && FaceletsTags.is(cursor.element(), "define") && cursor.attribute().value() != null) {
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

    private Location elDefinition(ElSite site, ElLookup.Hit hit) {
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
                String method = function.signature() == null ? hit.name() : Tags.javaMethodName(function);
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

    // ---------------------------------------------------------------- resources

    /**
     * A string key on a {@code ResourceHandler}, as in {@code #{resource['library:name']}}: the ELResolver for
     * resources answers such keys whatever expression yields the handler.
     */
    private boolean isResourceKey(ElLookup.Hit hit) {
        return hit != null && hit.kind() == ElLookup.Kind.KEY && hit.base() instanceof ElType.Java java
                && analyzer.isResourceHandler(java.type());
    }

    /** The files of {@code #{resource['library:name']}}, where the library {@code this} is the current file's. */
    private List<Location> resourceKey(String key) {
        Resources.Reference reference = Resources.Reference.parse(key);
        if (reference == null) {
            return List.of();
        }
        List<Path> files = "this".equals(reference.library())
                ? Resources.findFromThis(analyzer.project(), documentPath(), reference.name())
                : Resources.find(analyzer.project(), reference.library(), reference.name());
        return files.stream().map(DefinitionProvider::fileStart).toList();
    }

    /**
     * Attribute values that denote resources: {@code name} and {@code library} of a tag that takes both
     * ({@code h:graphicImage}, {@code h:outputScript}, {@code h:outputStylesheet}, and the like of component
     * libraries, also as {@code jsf:} attributes of passthrough elements), the URL of {@code h:graphicImage}, and the
     * contracts of {@code f:view}. Null for other attributes.
     */
    private List<Location> resourceAttribute(XmlElement element, XmlAttribute attribute, int offset) {
        String value = ElRegion.literal(attribute.value());
        String name = PassthroughElements.componentAttributeName(element, attribute);
        Tags.Resolved resolved = Tags.resolve(analyzer.project(), element);
        if (value == null || resolved.tag() == null || resolved.attribute(element, attribute) == null) {
            return null;
        }
        if ((name.equals("name") || name.equals("library"))
                && resolved.tag().attribute("name") != null && resolved.tag().attribute("library") != null) {
            String resourceName = name.equals("name") ? value : ElRegion.literal(componentAttributeValue(element, "name"));
            String library = name.equals("library") ? value : componentAttributeValue(element, "library");
            if (resourceName == null || library != null && ElRegion.literal(library) == null) {
                return List.of();
            }
            return Resources.find(analyzer.project(), library == null ? null : library.trim(), resourceName).stream()
                    .map(DefinitionProvider::fileStart).toList();
        }
        String namespace = Namespaces.canonical(resolved.namespace());
        if (resolved.passthrough() == null && Namespaces.HTML.equals(namespace)
                && element.localName().equals("graphicImage") && (name.equals("value") || name.equals("url"))) {
            Path image = resolveView(value);
            return image == null ? List.of() : List.of(fileStart(image));
        }
        if (Namespaces.CORE.equals(namespace) && element.localName().equals("view") && name.equals("contracts")) {
            String contract = listItemAt(attribute, offset);
            return contract == null ? List.of() : Resources.contract(analyzer.project(), contract).stream()
                    .map(DefinitionProvider::fileStart).toList();
        }
        return null;
    }

    /** The value of the attribute that has the given name on the component (a {@code jsf:} one on passthrough elements). */
    private static String componentAttributeValue(XmlElement element, String componentName) {
        boolean passthrough = PassthroughElements.isPassthroughElement(element);
        for (XmlAttribute candidate : element.attributes()) {
            if ((!passthrough || PassthroughElements.isFacesAttribute(element, candidate))
                    && PassthroughElements.componentAttributeName(element, candidate).equals(componentName)) {
                return candidate.value();
            }
        }
        return null;
    }

    /** The item of a comma separated attribute value under the offset, trimmed, or null. */
    private static String listItemAt(XmlAttribute attribute, int offset) {
        String value = attribute.value();
        int position = Math.max(0, Math.min(value.length(), offset - attribute.valueStart()));
        int start = value.lastIndexOf(',', position - 1) + 1;
        int end = value.indexOf(',', position);
        String item = value.substring(start, end < 0 ? value.length() : end).trim();
        return item.isEmpty() ? null : item;
    }

    private Path documentPath() {
        try {
            return Path.of(URI.create(document.uri()));
        } catch (RuntimeException e) {
            return null;
        }
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
        return descriptorLocation(resolved.library(), resolved.tag().name(), null);
    }

    /** The {@code cc:attribute} of a composite component, or else the attribute in the taglib descriptor. */
    private Location attributeDefinition(XmlElement element, XmlAttribute attribute) {
        Tags.Resolved resolved = Tags.resolve(analyzer.project(), element);
        if (resolved.tag() == null) {
            return null;
        }
        TagLibrary.AttributeDef definition = resolved.attribute(element, attribute);
        if (definition == null && resolved.passthrough() != null) {
            return null;
        }
        if (resolved.tag().fileUri() != null) {
            if (definition == null || definition.offset() < 0) {
                return null;
            }
            return atOffset(resolved.tag().fileUri(), definition.offset(), definition.name().length());
        }
        return descriptorLocation(resolved.library(), resolved.tag().name(), definition == null ? null : definition.name());
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
        String name = attribute.name();
        if (FaceletsTags.isViewReference(element, name)) {
            Path target = resolveView(value);
            return target == null ? null : fileStart(target);
        }
        if (Tags.namesJavaType(analyzer.imports(), element, name)) {
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
            List<ClientId> found = clientId == null ? List.of() : ClientIdLookup.find(analyzer, clientId);
            if (!found.isEmpty()) {
                for (ClientId target : found) {
                    FaceletsIndex.Entry entry = analyzer.index().entry(target.fileUri());
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

    /** The file a view path in the current file denotes ({@link ViewPaths#find}), or null. */
    private Path resolveView(String value) {
        return resolveView(document.uri(), value);
    }

    /** The file a view path in {@code fromUri}, which may be a file other than the current one, denotes, or null. */
    private Path resolveView(String fromUri, String value) {
        return ViewPaths.find(analyzer.project(), analyzer.index(), fromUri, value).orElse(null);
    }

    // ---------------------------------------------------------------- template slots

    /**
     * The {@code ui:insert}s a {@code ui:define} fills: in the template of the enclosing {@code ui:composition} or
     * {@code ui:decorate}, in the files that template includes, and up its own template chain for names it does not
     * define itself. Falls back to the start of the template when no slot has that name.
     */
    private List<Location> slots(XmlElement define, String name) {
        List<Location> result = new ArrayList<>();
        for (XmlElement client = define.parent(); client != null; client = client.parent()) {
            if (!(FaceletsTags.is(client, "composition") || FaceletsTags.is(client, "decorate"))) {
                continue;
            }
            String template = ElRegion.literal(client.attributeValue("template"));
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
            if (FaceletsTags.is(element, "composition")) {
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
        if (FaceletsTags.is(element, "insert")) {
            XmlAttribute insertName = element.attribute("name");
            if (insertName != null && insertName.value() != null && name.equals(insertName.value().trim())) {
                result.add(new Location(uri, lines.range(insertName.valueStart(), insertName.valueEnd())));
            }
        } else if (FaceletsTags.is(element, "include")) {
            String src = ElRegion.literal(element.attributeValue("src"));
            Path included = src == null ? null : resolveView(uri, src);
            if (included != null) {
                collectSlots(included, name, visited, result);
            }
        } else if ((FaceletsTags.is(element, "composition") || FaceletsTags.is(element, "decorate"))
                && ElRegion.literal(element.attributeValue("template")) != null) {
            // a template that is itself a template client: only its ui:defines are rendered, and the names it
            // does not define pass through to its own template
            List<XmlElement> defines = new ArrayList<>();
            collectDefines(element, defines);
            boolean definesName = defines.stream().anyMatch(d -> name.equals(trimmed(d.attributeValue("name"))));
            if (!definesName) {
                Path parent = resolveView(uri, ElRegion.literal(element.attributeValue("template")));
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
            if (FaceletsTags.is(child, "define")) {
                result.add(child);
            } else if (!FaceletsTags.is(child, "composition") && !FaceletsTags.is(child, "decorate")) {
                collectDefines(child, result);
            }
        }
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }
}
