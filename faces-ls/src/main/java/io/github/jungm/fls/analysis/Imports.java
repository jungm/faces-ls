package io.github.jungm.fls.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.jungm.fls.el.ElRegion;
import io.github.jungm.fls.taglib.Namespaces;
import io.github.jungm.fls.xml.XmlAttribute;
import io.github.jungm.fls.xml.XmlDocument;
import io.github.jungm.fls.xml.XmlElement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Tags that make a Java class available to EL under a variable or function prefix, by namespace and tag name.
 * Built in: {@code f:importConstants}, PrimeFaces' {@code p:importConstants} and {@code p:importEnum}, OmniFaces'
 * {@code o:importConstants} and {@code o:importFunctions}; the {@code imports} setting adds or removes tags. Every
 * such tag takes the class in {@code type} and the name in {@code var}, which defaults to the simple class name.
 */
public final class Imports {
    /** The key of {@code p:importEnum} for an array of all enum values. */
    public static final String ALL_VALUES = "ALL_VALUES";

    public enum Kind {
        /** Static fields as properties. */
        CONSTANTS,
        /** Enum constants as properties, plus {@link #ALL_VALUES} and the {@code allSuffix} key. */
        ENUM,
        /** Static methods as EL functions {@code var:method(...)}. */
        FUNCTIONS
    }

    /**
     * @param type          the imported class, with nested classes separated by '.'
     * @param typeAttribute the {@code type} attribute, for locations
     * @param allValuesKeys keys that yield all enum values ({@link Kind#ENUM} only)
     */
    public record Import(Kind kind, String type, String var, XmlElement element, XmlAttribute typeAttribute,
                         List<String> allValuesKeys) {
        /** The variable's declaration: the {@code var} value when given, else the type. */
        public int declarationOffset() {
            XmlAttribute var = element.attribute("var");
            return var != null && var.value() != null && !var.value().isBlank() ? var.valueStart() : typeAttribute.valueStart();
        }
    }

    public static final Imports DEFAULT = new Imports(builtIn());

    /** Canonical namespace to tag name to kind. */
    private final Map<String, Map<String, Kind>> tags;

    private Imports(Map<String, Map<String, Kind>> tags) {
        this.tags = tags;
    }

    private static Map<String, Map<String, Kind>> builtIn() {
        Map<String, Map<String, Kind>> tags = new LinkedHashMap<>();
        tags.put(Namespaces.CORE, new LinkedHashMap<>(Map.of("importConstants", Kind.CONSTANTS)));
        for (String primefaces : List.of("primefaces", "http://primefaces.org/ui")) {
            tags.put(primefaces, new LinkedHashMap<>(Map.of("importConstants", Kind.CONSTANTS, "importEnum", Kind.ENUM)));
        }
        for (String omnifaces : List.of("omnifaces", "http://omnifaces.org/ui")) {
            tags.put(omnifaces, new LinkedHashMap<>(Map.of("importConstants", Kind.CONSTANTS, "importFunctions", Kind.FUNCTIONS)));
        }
        return tags;
    }

    /**
     * The built-in tags with the {@code imports} setting applied:
     * {@code {"http://example.org/ui": {"importEnum": "enum", "importConstants": "constants", "importFunctions": "functions"}}}.
     * An empty string or {@code null} as the kind removes a built-in tag.
     */
    public static Imports from(JsonElement setting) {
        if (!(setting instanceof JsonObject namespaces)) {
            return DEFAULT;
        }
        Map<String, Map<String, Kind>> tags = builtIn();
        for (Map.Entry<String, JsonElement> namespace : namespaces.entrySet()) {
            if (!(namespace.getValue() instanceof JsonObject entries)) {
                continue;
            }
            Map<String, Kind> byTag = tags.computeIfAbsent(Namespaces.canonical(namespace.getKey()), k -> new LinkedHashMap<>());
            for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                Kind kind = parseKind(entry.getValue());
                if (kind == null) {
                    byTag.remove(entry.getKey());
                } else {
                    byTag.put(entry.getKey(), kind);
                }
            }
        }
        Map<String, Map<String, Kind>> frozen = new LinkedHashMap<>();
        tags.forEach((namespace, byTag) -> frozen.put(namespace, Map.copyOf(byTag)));
        return new Imports(Map.copyOf(frozen));
    }

    private static Kind parseKind(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || value.getAsString().isBlank()) {
            return null;
        }
        try {
            return Kind.valueOf(value.getAsString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** What kind of import the element is, or null. */
    public Kind kind(XmlElement element) {
        String namespace = Namespaces.canonical(element.namespace());
        Map<String, Kind> byTag = namespace == null ? null : tags.get(namespace);
        return byTag == null ? null : byTag.get(element.localName());
    }

    /** Whether the attribute names the class of an import tag. */
    public boolean isTypeAttribute(XmlElement element, String attributeName) {
        return attributeName.equals("type") && kind(element) != null;
    }

    /** The import an element declares, or null when it is none or its type or var is an expression. */
    public Import of(XmlElement element) {
        Kind kind = kind(element);
        XmlAttribute typeAttribute = element.attribute("type");
        String type = ElRegion.literal(typeAttribute == null ? null : typeAttribute.value());
        if (kind == null || type == null) {
            return null;
        }
        type = type.replace('$', '.');
        String var;
        XmlAttribute varAttribute = element.attribute("var");
        if (varAttribute != null && varAttribute.value() != null && !varAttribute.value().isBlank()) {
            var = ElRegion.literal(varAttribute.value());
            if (var == null) {
                return null;
            }
        } else {
            var = type.substring(type.lastIndexOf('.') + 1);
        }
        List<String> allValuesKeys = new ArrayList<>();
        if (kind == Kind.ENUM) {
            allValuesKeys.add(ALL_VALUES);
            String suffix = ElRegion.literal(element.attributeValue("allSuffix"));
            if (suffix != null && !suffix.equals(ALL_VALUES)) {
                allValuesKeys.add(suffix);
            }
        }
        return new Import(kind, type, var, element, typeAttribute, List.copyOf(allValuesKeys));
    }

    /** All imports of a document, in document order. */
    public List<Import> all(XmlDocument document) {
        List<Import> result = new ArrayList<>();
        for (XmlElement element : document.allElements()) {
            Import found = of(element);
            if (found != null) {
                result.add(found);
            }
        }
        return result;
    }

    /** The function import of a document whose prefix is {@code prefix}, or null. */
    public Import functions(XmlDocument document, String prefix) {
        for (Import found : all(document)) {
            if (found.kind() == Kind.FUNCTIONS && found.var().equals(prefix)) {
                return found;
            }
        }
        return null;
    }

    /** Namespaces with import tags, for logging. */
    public Map<String, Map<String, Kind>> tags() {
        return tags;
    }
}
