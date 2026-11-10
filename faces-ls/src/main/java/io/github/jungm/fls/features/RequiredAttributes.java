package io.github.jungm.fls.features;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.jungm.fls.project.ProjectModel;
import io.github.jungm.fls.taglib.Namespaces;
import io.github.jungm.fls.taglib.TagLibrary;
import io.github.jungm.fls.xml.XmlAttribute;
import io.github.jungm.fls.xml.XmlElement;

/**
 * Required attributes an element lacks, by its tag library definition.
 */
final class RequiredAttributes {
    /**
     * Attributes the taglib descriptors mark as required although Faces accepts the tag without them:
     * {@code ui:repeat} iterates over {@code begin}/{@code end} without {@code value}, and {@code var} is optional.
     */
    private static final Map<String, Set<String>> NOT_REQUIRED = Map.of(
            Namespaces.FACELETS + ":repeat", Set.of("value", "var"));

    private RequiredAttributes() {
    }

    static List<TagLibrary.AttributeDef> missing(ProjectModel project, XmlElement element) {
        Tags.Resolved resolved = Tags.resolve(project, element);
        if (resolved.tag() == null) {
            return List.of();
        }
        Set<String> exempt = NOT_REQUIRED.getOrDefault(Namespaces.canonical(resolved.namespace()) + ":" + element.localName(), Set.of());
        List<TagLibrary.AttributeDef> result = new ArrayList<>();
        for (TagLibrary.AttributeDef attribute : resolved.tag().attributes()) {
            if (resolved.passthrough() != null && attribute.name().equals(PassthroughElements.ELEMENT_NAME)) {
                continue;
            }
            if (attribute.required() && !exempt.contains(attribute.name()) && !isSet(element, attribute.name())) {
                result.add(attribute);
            }
        }
        return result;
    }

    /**
     * Set directly, in another namespace with the same local name ({@code pt:elementName} on {@code jsf:element}),
     * or through a nested {@code <f:attribute name="...">}.
     */
    private static boolean isSet(XmlElement element, String name) {
        for (XmlAttribute attribute : element.attributes()) {
            if (attribute.name().equals(name) || attribute.localName().equals(name) && !attribute.prefix().equals("xmlns")) {
                return true;
            }
        }
        for (XmlElement child : element.children()) {
            if (child.localName().equals("attribute") && Namespaces.CORE.equals(Namespaces.canonical(child.namespace()))
                    && name.equals(child.attributeValue("name"))) {
                return true;
            }
        }
        return false;
    }
}
