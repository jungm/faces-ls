package io.github.jungm.fls.features;

import java.util.Set;

import io.github.jungm.fls.project.ProjectModel;
import io.github.jungm.fls.taglib.Namespaces;
import io.github.jungm.fls.xml.XmlAttribute;
import io.github.jungm.fls.xml.XmlElement;

/**
 * Which elements of a page become components, naming containers or iterating components at runtime, judged from
 * the tag libraries.
 */
public final class ComponentModel {
    private static final Set<String> PRIMEFACES = Set.of("primefaces", "http://primefaces.org/ui");

    private static final Set<String> PRIMEFACES_NAMING_CONTAINERS = Set.of("dataTable", "dataList", "dataGrid",
            "dataView", "dataScroller", "treeTable", "tree", "tabView", "accordionPanel", "carousel", "subTable",
            "columns", "galleria", "chronoline");

    private final ProjectModel project;

    public ComponentModel(ProjectModel project) {
        this.project = project;
    }

    /** The project whose tag libraries decide. */
    public ProjectModel project() {
        return project;
    }

    /**
     * Tags whose taglib declares a component, composite components, {@code ui:repeat/component/fragment} and
     * passthrough elements ({@code <div jsf:id="...">}). Tag handlers and plain markup are not components.
     */
    public boolean isComponent(XmlElement element) {
        String namespace = Namespaces.canonical(element.namespace());
        if (Namespaces.FACELETS.equals(namespace)) {
            return Set.of("repeat", "component", "fragment").contains(element.localName());
        }
        if (isPassthroughElement(element)) {
            return true;
        }
        Tags.Resolved resolved = Tags.resolve(project, element);
        if (resolved.library() == null) {
            // a library whose taglib is not known (yet: classpath without jdtls): an element with an id is a component
            return !element.prefix().isEmpty() && resolved.namespace() != null && element.attribute("id") != null;
        }
        if (resolved.tag() == null) {
            return false;
        }
        return resolved.library().composite() || resolved.tag().componentType() != null
                || Namespaces.PASSTHROUGH_ELEMENTS.equals(Namespaces.canonical(resolved.namespace()));
    }

    static boolean isPassthroughElement(XmlElement element) {
        for (XmlAttribute attribute : element.attributes()) {
            if (!attribute.prefix().isEmpty() && !attribute.prefix().equals("xmlns")
                    && Namespaces.PASSTHROUGH_ELEMENTS.equals(Namespaces.canonical(element.namespaceFor(attribute.prefix())))) {
                return true;
            }
        }
        return false;
    }

    public boolean isNamingContainer(XmlElement element) {
        if (!isComponent(element)) {
            return false;
        }
        String namespace = Namespaces.canonical(element.namespace());
        String local = element.localName();
        if (local.equals("form")) {
            return true;
        }
        if (Namespaces.HTML.equals(namespace)) {
            return local.equals("dataTable");
        }
        if (Namespaces.FACELETS.equals(namespace)) {
            return local.equals("repeat");
        }
        if (Namespaces.CORE.equals(namespace)) {
            return local.equals("subview");
        }
        if (PRIMEFACES.contains(element.namespace())) {
            return PRIMEFACES_NAMING_CONTAINERS.contains(local);
        }
        return isComposite(element);
    }

    public boolean isComposite(XmlElement element) {
        Tags.Resolved resolved = Tags.resolve(project, element);
        return resolved.library() != null && resolved.library().composite() && resolved.tag() != null
                && resolved.tag().fileUri() != null;
    }

    /** Naming containers that repeat their children per row ({@code var}): client ids inside get a row index. */
    public boolean isIterating(XmlElement element) {
        if (!isNamingContainer(element)) {
            return false;
        }
        return element.attribute("var") != null || element.localName().equals("repeat");
    }

    /** The component id: {@code id}, or {@code jsf:id} on passthrough elements. */
    public static String idOf(XmlElement element) {
        XmlAttribute attribute = idAttribute(element);
        return attribute == null || attribute.value() == null ? null : attribute.value().trim();
    }

    public static XmlAttribute idAttribute(XmlElement element) {
        XmlAttribute id = element.attribute("id");
        if (id != null) {
            return id;
        }
        for (XmlAttribute attribute : element.attributes()) {
            if (attribute.localName().equals("id") && !attribute.prefix().isEmpty()
                    && Namespaces.PASSTHROUGH_ELEMENTS.equals(Namespaces.canonical(element.namespaceFor(attribute.prefix())))) {
                return attribute;
            }
        }
        return null;
    }
}
