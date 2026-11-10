package io.github.jungm.fls.features;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.jungm.fls.analysis.Imports;
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

    /**
     * @param passthrough the tag a passthrough element ({@code <input jsf:value="...">}) is processed as, or null when
     *                    the element is that tag itself
     */
    record Resolved(String namespace, TagLibrary library, TagLibrary.TagDef tag, PassthroughElements.Target passthrough) {
        /**
         * The definition of an attribute of the element. On passthrough elements only {@code jsf:} attributes are
         * attributes of the tag; the others are rendered as they are.
         */
        TagLibrary.AttributeDef attribute(XmlElement element, XmlAttribute attribute) {
            if (tag == null) {
                return null;
            }
            if (passthrough != null) {
                return PassthroughElements.isFacesAttribute(element, attribute) ? tag.attribute(attribute.localName()) : null;
            }
            return tag.attribute(attribute.name());
        }

        /** The tag's name as written in a page: the element's name, or the target tag of a passthrough element. */
        String tagName(XmlElement element) {
            return passthrough != null ? passthrough.qualifiedName() : element.name();
        }
    }

    /**
     * The library and tag definition for an element; {@code library} is null for plain (X)HTML markup. Passthrough
     * elements resolve to the tag they are processed as.
     */
    static Resolved resolve(ProjectModel project, XmlElement element) {
        String namespace = element.namespace();
        if (namespace == null || Namespaces.XHTML.equals(namespace)) {
            PassthroughElements.Target target = PassthroughElements.target(element);
            if (target == null) {
                return new Resolved(namespace, null, null, null);
            }
            TagLibrary library = project.taglibs().library(target.namespace());
            TagLibrary.TagDef tag = library == null ? null : library.tags().get(target.localName());
            return new Resolved(target.namespace(), library, tag, target);
        }
        TagLibrary library = project.taglibs().library(namespace);
        TagLibrary.TagDef tag = library == null ? null : library.tags().get(element.localName());
        return new Resolved(namespace, library, tag, null);
    }

    /** Whether the attribute's value is a Java class name: {@code type} of {@code cc:attribute} or of an import tag. */
    static boolean namesJavaType(Imports imports, XmlElement element, String attributeName) {
        return attributeName.equals("type")
                && (Namespaces.COMPOSITE.equals(Namespaces.canonical(element.namespace())) && element.localName().equals("attribute")
                || imports.isTypeAttribute(element, attributeName));
    }

    /**
     * The name of the Java method that implements a taglib function, from its signature such as
     * {@code int length(java.lang.Object)}; the function's own name may differ.
     */
    static String javaMethodName(TagLibrary.FunctionDef function) {
        return function.signature().replaceFirst("\\s*\\(.*", "").replaceFirst(".*\\s", "");
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
