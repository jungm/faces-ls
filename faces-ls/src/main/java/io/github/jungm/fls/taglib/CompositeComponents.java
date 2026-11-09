package io.github.jungm.fls.taglib;

import java.util.ArrayList;
import java.util.List;

import io.github.jungm.fls.xml.XmlAttribute;
import io.github.jungm.fls.xml.XmlDocument;
import io.github.jungm.fls.xml.XmlElement;

/**
 * Reads the {@code cc:interface} of a composite component file.
 */
public final class CompositeComponents {
    private CompositeComponents() {
    }

    public static XmlElement findInterface(XmlDocument document) {
        for (XmlElement element : document.allElements()) {
            if (element.localName().equals("interface") && Namespaces.COMPOSITE.equals(Namespaces.canonical(element.namespace()))) {
                return element;
            }
        }
        return null;
    }

    public static XmlElement findImplementation(XmlDocument document) {
        for (XmlElement element : document.allElements()) {
            if (element.localName().equals("implementation")
                    && Namespaces.COMPOSITE.equals(Namespaces.canonical(element.namespace()))) {
                return element;
            }
        }
        return null;
    }

    public static TagLibrary.TagDef describe(String name, String fileUri, String text) {
        XmlDocument document = XmlDocument.parse(text);
        XmlElement contract = findInterface(document);
        List<TagLibrary.AttributeDef> attributes = new ArrayList<>();
        String description = null;
        String componentType = null;
        if (contract != null) {
            description = contract.attributeValue("shortDescription");
            componentType = contract.attributeValue("componentType");
            for (XmlElement child : contract.children()) {
                if (!child.localName().equals("attribute")) {
                    continue;
                }
                String attributeName = child.attributeValue("name");
                if (attributeName == null) {
                    continue;
                }
                XmlAttribute nameAttribute = child.attribute("name");
                attributes.add(new TagLibrary.AttributeDef(attributeName,
                        child.attributeValue("shortDescription"),
                        "true".equalsIgnoreCase(child.attributeValue("required")),
                        child.attributeValue("type"),
                        child.attributeValue("method-signature"),
                        child.attributeValue("default"),
                        nameAttribute.valueStart()));
            }
            // the implicit attributes every component supports
            for (String implicit : List.of("id", "rendered", "binding")) {
                if (attributes.stream().noneMatch(a -> a.name().equals(implicit))) {
                    attributes.add(new TagLibrary.AttributeDef(implicit, null, false, null, null, null, -1));
                }
            }
        }
        return new TagLibrary.TagDef(name, description, attributes, fileUri, componentType);
    }
}
