package io.github.jungm.fls.taglib;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Reads {@code *.taglib.xml} (Facelets tag library descriptor) files.
 */
public final class TaglibXmlLoader {
    private TaglibXmlLoader() {
    }

    public static TagLibrary load(InputStream input, String source) throws IOException {
        return load(input, source, null);
    }

    public static TagLibrary load(InputStream input, String source, TaglibDescriptor descriptor) throws IOException {
        Document document = parse(input);
        Element root = document.getDocumentElement();
        String namespace = childText(root, "namespace");
        if (namespace == null) {
            return null;
        }
        TagLibrary.Builder library = TagLibrary.builder(namespace.trim(), source)
                .description(Html.toMarkdown(childText(root, "description")))
                .compositeLibraryName(trimmed(childText(root, "composite-library-name")))
                .descriptor(descriptor);
        for (Element tag : children(root, "tag")) {
            String name = childText(tag, "tag-name");
            if (name == null) {
                continue;
            }
            List<TagLibrary.AttributeDef> attributes = new ArrayList<>();
            for (Element attribute : children(tag, "attribute")) {
                String attributeName = childText(attribute, "name");
                if (attributeName == null) {
                    continue;
                }
                attributes.add(new TagLibrary.AttributeDef(attributeName.trim(),
                        Html.toMarkdown(childText(attribute, "description")),
                        "true".equalsIgnoreCase(trimmed(childText(attribute, "required"))),
                        trimmed(childText(attribute, "type")),
                        trimmed(childText(attribute, "method-signature")),
                        null, -1));
            }
            String componentType = null;
            Element component = first(tag, "component");
            if (component != null) {
                componentType = trimmed(childText(component, "component-type"));
            }
            library.tag(new TagLibrary.TagDef(name.trim(), Html.toMarkdown(childText(tag, "description")), attributes,
                    null, componentType));
        }
        for (Element function : children(root, "function")) {
            String name = childText(function, "function-name");
            if (name == null) {
                continue;
            }
            library.function(new TagLibrary.FunctionDef(name.trim(), trimmed(childText(function, "function-class")),
                    trimmed(childText(function, "function-signature")), Html.toMarkdown(childText(function, "description"))));
        }
        return library.build();
    }

    private static Document parse(InputStream input) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            return builder.parse(new InputSource(input));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException(e);
        }
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private static String localName(Node node) {
        String name = node.getNodeName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && localName(element).equals(name)) {
                result.add(element);
            }
        }
        return result;
    }

    static Element first(Element parent, String name) {
        List<Element> all = children(parent, name);
        return all.isEmpty() ? null : all.get(0);
    }

    static String childText(Element parent, String name) {
        List<Element> all = children(parent, name);
        if (all.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (Element element : all) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(element.getTextContent());
        }
        return text.toString();
    }
}
