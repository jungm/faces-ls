package io.github.jungm.fls.taglib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tags and functions available under one namespace.
 *
 * @param namespace  the namespace URI as declared in the taglib (or the composite library URI)
 * @param source     where the library was loaded from, for hover text
 * @param composite  true for composite component libraries (tags are .xhtml files)
 * @param attributesComplete false when the library does not declare attributes, so unknown attributes must not
 *                   be reported
 * @param compositeLibraryName resource library whose composite components the taglib also exposes
 *                   ({@code <composite-library-name>}), or null
 * @param descriptor the {@code *.taglib.xml} the library was read from, or null for composite libraries
 */
public record TagLibrary(String namespace, String source, boolean composite, boolean attributesComplete,
                         Map<String, TagDef> tags, Map<String, FunctionDef> functions, String description,
                         String compositeLibraryName, TaglibDescriptor descriptor) {

    public record TagDef(String name, String description, List<AttributeDef> attributes, String fileUri,
                         String componentType) {
        public AttributeDef attribute(String attributeName) {
            for (AttributeDef attribute : attributes) {
                if (attribute.name().equals(attributeName)) {
                    return attribute;
                }
            }
            return null;
        }
    }

    /**
     * @param offset offset of the declaring {@code cc:attribute} in {@code TagDef#fileUri} for composite
     *               components, otherwise -1
     */
    public record AttributeDef(String name, String description, boolean required, String type, String methodSignature,
                               String defaultValue, int offset) {
    }

    public record FunctionDef(String name, String functionClass, String signature, String description) {
        /** Parameter types parsed from a signature such as {@code int length(java.lang.Object)}. */
        public List<String> parameterTypes() {
            int open = signature.indexOf('(');
            int close = signature.lastIndexOf(')');
            List<String> types = new ArrayList<>();
            if (open < 0 || close < open) {
                return types;
            }
            String inner = signature.substring(open + 1, close).trim();
            if (inner.isEmpty()) {
                return types;
            }
            for (String part : inner.split(",")) {
                types.add(part.trim());
            }
            return types;
        }

        public String returnType() {
            int open = signature.indexOf('(');
            String head = open < 0 ? signature : signature.substring(0, open).trim();
            int space = head.lastIndexOf(' ');
            return space < 0 ? "java.lang.Object" : head.substring(0, space).trim();
        }
    }

    public static Builder builder(String namespace, String source) {
        return new Builder(namespace, source);
    }

    public static final class Builder {
        private final String namespace;
        private final String source;
        private boolean composite;
        private String description;
        private String compositeLibraryName;
        private TaglibDescriptor descriptor;
        private final Map<String, TagDef> tags = new LinkedHashMap<>();
        private final Map<String, FunctionDef> functions = new LinkedHashMap<>();

        private Builder(String namespace, String source) {
            this.namespace = namespace;
            this.source = source;
        }

        public Builder composite(boolean value) {
            this.composite = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder descriptor(TaglibDescriptor value) {
            this.descriptor = value;
            return this;
        }

        public Builder compositeLibraryName(String value) {
            this.compositeLibraryName = value;
            return this;
        }

        public Builder tags(Map<String, TagDef> values) {
            tags.putAll(values);
            return this;
        }

        public Builder functions(Map<String, FunctionDef> values) {
            functions.putAll(values);
            return this;
        }

        public Builder tag(TagDef tag) {
            tags.put(tag.name(), tag);
            return this;
        }

        public Builder function(FunctionDef function) {
            functions.put(function.name(), function);
            return this;
        }

        public TagLibrary build() {
            boolean complete = composite || tags.values().stream().anyMatch(t -> !t.attributes().isEmpty());
            return new TagLibrary(namespace, source, composite, complete, Map.copyOf(tags), Map.copyOf(functions), description,
                    compositeLibraryName, descriptor);
        }
    }
}
