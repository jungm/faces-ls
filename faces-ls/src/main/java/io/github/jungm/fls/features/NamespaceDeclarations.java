package io.github.jungm.fls.features;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.lsp4j.TextEdit;
import io.github.jungm.fls.FacesSettings;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.el.ElNode;
import io.github.jungm.fls.project.ProjectModel;
import io.github.jungm.fls.taglib.Namespaces;
import io.github.jungm.fls.taglib.TagLibrary;
import io.github.jungm.fls.xml.XmlAttribute;
import io.github.jungm.fls.xml.XmlElement;

/**
 * Predefined namespace prefixes: which library a prefix stands for in this project, which prefixes a page uses
 * without declaring them, and the edit that declares one on the root element.
 */
public final class NamespaceDeclarations {
    private final FacesSettings settings;
    private final ProjectModel project;
    private final FacesDocument document;

    public NamespaceDeclarations(FacesSettings settings, ProjectModel project, FacesDocument document) {
        this.settings = settings;
        this.project = project;
        this.document = document;
    }

    /** A predefined prefix resolved for this project. */
    public record Predefined(String prefix, String uri, TagLibrary library) {
    }

    /**
     * The URI and library a predefined prefix stands for: the first candidate URI whose library exists in this
     * project (standard libraries always do; their URI follows the project's jakarta/javax generation).
     */
    public Predefined resolve(String prefix) {
        List<String> candidates = settings.namespaces().get(prefix);
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            String uri = Namespaces.isStandard(candidate) ? Tags.preferredUri(project, Namespaces.canonical(candidate)) : candidate;
            TagLibrary library = project.taglibs().library(uri);
            if (library != null && (!library.tags().isEmpty() || !library.functions().isEmpty())) {
                return new Predefined(prefix, uri, library);
            }
        }
        return null;
    }

    /** Predefined prefixes that are not declared at the element and whose library is not declared either. */
    public Map<String, Predefined> available(XmlElement context) {
        Map<String, String> declared = context == null ? Map.of() : Tags.declaredPrefixes(context);
        Set<String> declaredLibraries = new HashSet<>();
        declared.values().forEach(uri -> declaredLibraries.add(Namespaces.canonical(uri)));
        Map<String, Predefined> result = new LinkedHashMap<>();
        for (String prefix : settings.namespaces().keySet()) {
            if (declared.containsKey(prefix)) {
                continue;
            }
            Predefined predefined = resolve(prefix);
            if (predefined != null && !declaredLibraries.contains(Namespaces.canonical(predefined.uri()))) {
                result.put(prefix, predefined);
            }
        }
        return result;
    }

    /**
     * Prefixes used by elements, attributes and EL functions without a declaration in scope, in document order, with
     * the ranges ({@code [start, end]}) where each occurs.
     */
    public Map<String, List<int[]>> undeclaredPrefixes() {
        Map<String, List<int[]>> result = new LinkedHashMap<>();
        for (XmlElement element : document.xml().allElements()) {
            if (!element.prefix().isEmpty() && element.namespace() == null) {
                result.computeIfAbsent(element.prefix(), p -> new ArrayList<>())
                        .add(new int[] {element.nameStart(), element.nameStart() + element.prefix().length()});
            }
            for (XmlAttribute attribute : element.attributes()) {
                String prefix = attribute.prefix();
                if (!prefix.isEmpty() && !prefix.equals("xmlns") && !prefix.equals("xml") && element.namespaceFor(prefix) == null) {
                    result.computeIfAbsent(prefix, p -> new ArrayList<>())
                            .add(new int[] {attribute.nameStart(), attribute.nameStart() + prefix.length()});
                }
            }
        }
        for (FacesDocument.ElSite site : document.elSites()) {
            site.parse().root().walk(node -> {
                if (node instanceof ElNode.Function function && site.element() != null
                        && site.element().namespaceFor(function.prefix()) == null) {
                    result.computeIfAbsent(function.prefix(), p -> new ArrayList<>())
                            .add(new int[] {function.prefixStart(), function.prefixStart() + function.prefix().length()});
                }
            });
        }
        return result;
    }

    /**
     * Inserts {@code xmlns:prefix="uri"} on the root element: after the last namespace declaration, on its own line
     * aligned with it when the root's attributes span several lines, otherwise on the same line.
     */
    public TextEdit declare(String prefix, String uri) {
        XmlElement root = document.xml().root();
        if (root == null) {
            return null;
        }
        String declaration = "xmlns:" + prefix + "=\"" + uri + "\"";
        XmlAttribute anchor = null;
        for (XmlAttribute attribute : root.attributes()) {
            if (attribute.name().equals("xmlns") || attribute.name().startsWith("xmlns:")) {
                anchor = attribute;
            }
        }
        String text = document.text();
        if (anchor == null || !anchor.hasValue()) {
            return new TextEdit(document.lines().range(root.nameEnd(), root.nameEnd()), " " + declaration);
        }
        int insertAt = Math.min(text.length(), anchor.valueEnd() + 1);
        boolean multiline = root.attributes().stream()
                .anyMatch(a -> document.lines().position(a.nameStart()).getLine() != document.lines().position(root.nameStart()).getLine());
        if (multiline) {
            int lineStart = text.lastIndexOf('\n', anchor.nameStart() - 1) + 1;
            String indent = " ".repeat(Math.max(0, anchor.nameStart() - lineStart));
            if (text.substring(lineStart, anchor.nameStart()).contains("\t")) {
                indent = text.substring(lineStart, anchor.nameStart()).replaceAll("\\S", " ");
            }
            return new TextEdit(document.lines().range(insertAt, insertAt), "\n" + indent + declaration);
        }
        return new TextEdit(document.lines().range(insertAt, insertAt), " " + declaration);
    }
}
