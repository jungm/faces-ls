package io.github.jungm.fls.features;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.ElType;
import io.github.jungm.fls.el.ElRegion;
import io.github.jungm.fls.index.ViewPaths;
import io.github.jungm.fls.project.Resources;
import io.github.jungm.fls.taglib.Namespaces;
import io.github.jungm.fls.xml.XmlAttribute;
import io.github.jungm.fls.xml.XmlElement;

/**
 * The files resource references in a page denote, for go to definition and hover: the {@code name} and
 * {@code library} of resource tags, the URL of {@code h:graphicImage}, the contracts of {@code f:view} and the keys
 * of {@code #{resource['library:name']}}.
 */
final class ResourceReferences {
    /** The files a reference resolves to (best first, possibly none) and the range of the reference. */
    record Target(List<Path> files, int start, int end) {
    }

    private ResourceReferences() {
    }

    /**
     * A string key on a {@code ResourceHandler}, as in {@code #{resource['library:name']}}: the ELResolver for
     * resources answers such keys whatever expression yields the handler.
     */
    static boolean isResourceKey(ElAnalyzer analyzer, ElLookup.Hit hit) {
        return hit != null && hit.kind() == ElLookup.Kind.KEY && hit.base() instanceof ElType.Java java
                && analyzer.isResourceHandler(java.type());
    }

    /** The files of {@code #{resource['library:name']}}, where the library {@code this} is the current file's. */
    static List<Path> key(ElAnalyzer analyzer, String key) {
        Resources.Reference reference = Resources.Reference.parse(key);
        if (reference == null) {
            return List.of();
        }
        return "this".equals(reference.library())
                ? Resources.findFromThis(analyzer.project(), documentPath(analyzer), reference.name())
                : Resources.find(analyzer.project(), reference.library(), reference.name());
    }

    /**
     * Attribute values that denote resources: {@code name} and {@code library} of a tag that takes both
     * ({@code h:graphicImage}, {@code h:outputScript}, {@code h:outputStylesheet}, and the like of component
     * libraries, also as {@code jsf:} attributes of passthrough elements), the URL of {@code h:graphicImage}, and the
     * contracts of {@code f:view}. Null for other attributes.
     */
    static Target attribute(ElAnalyzer analyzer, XmlElement element, XmlAttribute attribute, int offset) {
        String value = ElRegion.literal(attribute.value());
        String name = PassthroughElements.componentAttributeName(element, attribute);
        Tags.Resolved resolved = Tags.resolve(analyzer.project(), element);
        if (value == null || resolved.tag() == null || resolved.attribute(element, attribute) == null) {
            return null;
        }
        if ((name.equals("name") || name.equals("library")) && resolved.takesResource()) {
            String resourceName = name.equals("name") ? value : ElRegion.literal(PassthroughElements.componentAttributeValue(element, "name"));
            String library = name.equals("library") ? value : PassthroughElements.componentAttributeValue(element, "library");
            List<Path> files = resourceName == null || library != null && ElRegion.literal(library) == null ? List.of()
                    : Resources.find(analyzer.project(), library == null ? null : library.trim(), resourceName);
            return new Target(files, attribute.valueStart(), attribute.valueEnd());
        }
        String namespace = Namespaces.canonical(resolved.namespace());
        if (resolved.passthrough() == null && Namespaces.HTML.equals(namespace)
                && element.localName().equals("graphicImage") && (name.equals("value") || name.equals("url"))) {
            Path image = ViewPaths.find(analyzer.project(), analyzer.index(), analyzer.document().uri(), value).orElse(null);
            return new Target(image == null ? List.of() : List.of(image), attribute.valueStart(), attribute.valueEnd());
        }
        if (Namespaces.CORE.equals(namespace) && element.localName().equals("view") && name.equals("contracts")) {
            return contractAt(analyzer, attribute, offset);
        }
        return null;
    }

    /** The contract of a comma separated {@code contracts} value under the offset. */
    private static Target contractAt(ElAnalyzer analyzer, XmlAttribute attribute, int offset) {
        String value = attribute.value();
        int position = Math.max(0, Math.min(value.length(), offset - attribute.valueStart()));
        int start = value.lastIndexOf(',', position - 1) + 1;
        int end = value.indexOf(',', position);
        end = end < 0 ? value.length() : end;
        while (start < end && Character.isWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        String contract = value.substring(start, end);
        List<Path> files = contract.isEmpty() ? List.of() : Resources.contract(analyzer.project(), contract);
        return new Target(files, attribute.valueStart() + start, attribute.valueStart() + end);
    }

    private static Path documentPath(ElAnalyzer analyzer) {
        try {
            return Path.of(URI.create(analyzer.document().uri()));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
