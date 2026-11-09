package io.github.jungm.fls.index;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.jungm.fls.features.ComponentModel;
import io.github.jungm.fls.index.FaceletsIndex.Edge;
import io.github.jungm.fls.index.FaceletsIndex.Entry;
import io.github.jungm.fls.index.FaceletsIndex.Kind;
import io.github.jungm.fls.index.FaceletsIndex.Snapshot;
import io.github.jungm.fls.taglib.Namespaces;
import io.github.jungm.fls.xml.XmlAttribute;
import io.github.jungm.fls.xml.XmlElement;

/**
 * Client ids of the components of whole pages: each page is expanded through its templates ({@code ui:define}
 * into {@code ui:insert}), includes and composite components, and every component with an id gets the ids of its
 * enclosing naming containers as prefix.
 *
 * <p>Only stable client ids are produced: below a naming container without id (generated {@code j_id...}) or with
 * an EL id, and inside iterating components ({@code ui:repeat}, data tables) whose children get a row index, there
 * is nothing to reference statically.
 */
public final class ClientIds {

    /**
     * @param id        client id without leading separator, e.g. {@code form:panel:name}
     * @param fileUri   file declaring the component
     * @param start     start of the id attribute value in that file
     * @param end       end of the id attribute value
     * @param tag       element name of the component
     * @param pageUri   page the client id was computed for
     */
    public record ClientId(String id, char separator, String fileUri, int start, int end, String tag, String pageUri) {
        public String absolute() {
            return separator + id;
        }
    }

    private ClientIds() {
    }

    /** All stable client ids of a page, cached per index snapshot. */
    public static List<ClientId> forPage(Snapshot snapshot, ComponentModel components, String pageUri) {
        return snapshot.cached("clientIds:" + System.identityHashCode(components) + ":" + pageUri, () -> {
            Entry page = snapshot.entry(pageUri);
            if (page == null) {
                return List.of();
            }
            Expander expander = new Expander(snapshot, components, pageUri, snapshot.separatorFor(page));
            expander.expandFile(pageUri, List.of(), true, new ArrayDeque<>());
            return List.copyOf(expander.result);
        });
    }

    /**
     * Client ids of every page the file ends up in, one per distinct id (the first occurrence wins), and how many
     * pages each id occurs in. A file used by no page is expanded on its own.
     */
    public static Map<String, List<ClientId>> forFile(Snapshot snapshot, ComponentModel components, String uri) {
        Set<String> pages = snapshot.pagesContaining(uri);
        List<String> expand = pages.isEmpty() ? List.of(uri) : List.copyOf(pages);
        Map<String, List<ClientId>> byId = new LinkedHashMap<>();
        for (String page : expand) {
            for (ClientId clientId : forPage(snapshot, components, page)) {
                byId.computeIfAbsent(clientId.id(), k -> new ArrayList<>()).add(clientId);
            }
        }
        return byId;
    }

    /** Defines of a template client: name to the ui:define element and the file it is in. */
    private record Define(XmlElement element, String uri) {
    }

    private static final class Expander {
        private final Snapshot snapshot;
        private final ComponentModel components;
        private final String pageUri;
        private final char separator;
        private final List<ClientId> result = new ArrayList<>();
        private final Deque<String> stack = new ArrayDeque<>();

        Expander(Snapshot snapshot, ComponentModel components, String pageUri, char separator) {
            this.snapshot = snapshot;
            this.components = components;
            this.pageUri = pageUri;
            this.separator = separator;
        }

        void expandFile(String uri, List<String> prefix, boolean stable, Deque<Map<String, Define>> defines) {
            Entry entry = snapshot.entry(uri);
            if (entry == null || stack.contains(uri) || stack.size() > 40) {
                return;
            }
            stack.push(uri);
            try {
                for (XmlElement root : entry.xml().roots()) {
                    walk(uri, root, prefix, stable, defines);
                }
            } finally {
                stack.pop();
            }
        }

        private void walk(String uri, XmlElement element, List<String> prefix, boolean stable, Deque<Map<String, Define>> defines) {
            String namespace = Namespaces.canonical(element.namespace());
            String local = element.localName();
            if (Namespaces.FACELETS.equals(namespace)) {
                switch (local) {
                    case "define" -> {
                        // reached only when the template is unknown (not indexed): its naming containers are unknown,
                        // so the content gets no absolute client ids
                        walkChildren(uri, element, prefix, false, defines);
                        return;
                    }
                    case "insert" -> {
                        Define define = lookup(defines, element.attributeValue("name"));
                        if (define != null) {
                            for (XmlElement child : define.element().children()) {
                                walk(define.uri(), child, prefix, stable, defines);
                            }
                        } else {
                            walkChildren(uri, element, prefix, stable, defines);
                        }
                        return;
                    }
                    case "composition", "decorate" -> {
                        Edge template = edge(uri, element, Kind.TEMPLATE);
                        if (template != null) {
                            Deque<Map<String, Define>> nested = new ArrayDeque<>(defines);
                            nested.addFirst(definesOf(uri, element));
                            expandFile(template.to(), prefix, stable, nested);
                            return;
                        }
                    }
                    case "include" -> {
                        Edge include = edge(uri, element, Kind.INCLUDE);
                        if (include != null) {
                            expandFile(include.to(), prefix, stable, defines);
                        }
                        return;
                    }
                    default -> {
                        // ui:repeat, ui:fragment, ... are handled as components below
                    }
                }
            }

            Edge composite = edge(uri, element, Kind.COMPOSITE);
            boolean component = composite != null || components.isComponent(element);
            if (!component) {
                walkChildren(uri, element, prefix, stable, defines);
                return;
            }
            XmlAttribute idAttribute = ComponentModel.idAttribute(element);
            String id = idAttribute == null || idAttribute.value() == null ? null : idAttribute.value().trim();
            boolean stableId = id != null && !id.isEmpty() && !id.contains("#{") && !id.contains("${");
            if (stable && stableId) {
                result.add(new ClientId(join(prefix, id), separator, uri, idAttribute.valueStart(), idAttribute.valueEnd(),
                        element.name(), pageUri));
            }
            boolean container = composite != null || components.isNamingContainer(element);
            List<String> inner = prefix;
            boolean innerStable = stable;
            if (container) {
                inner = new ArrayList<>(prefix);
                inner.add(stableId ? id : "?");
                innerStable = stable && stableId && !components.isIterating(element);
            }
            if (composite != null) {
                Entry implementation = snapshot.entry(composite.to());
                XmlElement body = implementation == null ? null : findImplementation(implementation);
                if (body != null) {
                    // the composite's own defines do not leak into it
                    for (XmlElement child : body.children()) {
                        walk(composite.to(), child, inner, innerStable, defines);
                    }
                }
            }
            // children of the element (for a composite: what cc:insertChildren / facets render inside it)
            walkChildren(uri, element, inner, innerStable, defines);
        }

        private void walkChildren(String uri, XmlElement element, List<String> prefix, boolean stable,
                                  Deque<Map<String, Define>> defines) {
            for (XmlElement child : element.children()) {
                walk(uri, child, prefix, stable, defines);
            }
        }

        private Edge edge(String uri, XmlElement element, Kind kind) {
            Edge edge = snapshot.edgeAt(uri, element);
            return edge != null && edge.kind() == kind ? edge : null;
        }

        private static Define lookup(Deque<Map<String, Define>> defines, String name) {
            if (name == null) {
                return null;
            }
            for (Map<String, Define> level : defines) {
                Define define = level.get(name);
                if (define != null) {
                    return define;
                }
            }
            return null;
        }

        private static Map<String, Define> definesOf(String uri, XmlElement client) {
            Map<String, Define> result = new HashMap<>();
            collectDefines(uri, client, result);
            return result;
        }

        private static void collectDefines(String uri, XmlElement element, Map<String, Define> result) {
            for (XmlElement child : element.children()) {
                if (child.localName().equals("define") && Namespaces.FACELETS.equals(Namespaces.canonical(child.namespace()))) {
                    String name = child.attributeValue("name");
                    if (name != null) {
                        result.putIfAbsent(name, new Define(child, uri));
                    }
                } else {
                    collectDefines(uri, child, result);
                }
            }
        }

        private static XmlElement findImplementation(Entry entry) {
            for (XmlElement element : entry.xml().allElements()) {
                if (element.localName().equals("implementation")
                        && Namespaces.COMPOSITE.equals(Namespaces.canonical(element.namespace()))) {
                    return element;
                }
            }
            return null;
        }

        private String join(List<String> prefix, String id) {
            if (prefix.isEmpty()) {
                return id;
            }
            StringBuilder text = new StringBuilder();
            for (String part : prefix) {
                text.append(part).append(separator);
            }
            return text.append(id).toString();
        }
    }
}
