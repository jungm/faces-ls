package io.github.jungm.fls.features;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.jungm.fls.project.ProjectModel;
import io.github.jungm.fls.taglib.Namespaces;
import io.github.jungm.fls.taglib.TagLibrary;
import io.github.jungm.fls.xml.XmlAttribute;
import io.github.jungm.fls.xml.XmlElement;

/**
 * Looks up tag library definitions for elements.
 */
final class Tags {
    private Tags() {
    }

    record Resolved(String namespace, TagLibrary library, TagLibrary.TagDef tag) {
    }

    /** The library and tag definition for an element; {@code library} is null for plain (X)HTML markup. */
    static Resolved resolve(ProjectModel project, XmlElement element) {
        String namespace = element.namespace();
        if (namespace == null || Namespaces.XHTML.equals(namespace)) {
            return new Resolved(namespace, null, null);
        }
        TagLibrary library = project.taglibs().library(namespace);
        TagLibrary.TagDef tag = library == null ? null : library.tags().get(element.localName());
        return new Resolved(namespace, library, tag);
    }

    /** Namespace prefixes declared on the element and its ancestors, innermost first. */
    static Map<String, String> declaredPrefixes(XmlElement element) {
        Map<String, String> prefixes = new LinkedHashMap<>();
        for (XmlElement current = element; current != null; current = current.parent()) {
            for (XmlAttribute attribute : current.attributes()) {
                if (attribute.name().startsWith("xmlns:") && attribute.value() != null) {
                    prefixes.putIfAbsent(attribute.name().substring(6), attribute.value().trim());
                }
            }
        }
        return prefixes;
    }

    /** The URI to declare for a standard library, matching the Faces generation of the project. */
    static String preferredUri(ProjectModel project, String canonical) {
        if (project.facesPackage().equals("jakarta")) {
            return canonical;
        }
        for (Map.Entry<String, String> alias : Namespaces.knownUris().entrySet()) {
            if (alias.getValue().equals(canonical) && alias.getKey().startsWith("http://xmlns.jcp.org/")) {
                return alias.getKey();
            }
        }
        return canonical;
    }
}
