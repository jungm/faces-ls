package io.github.jungm.fls.features;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.project.ProjectModel;
import io.github.jungm.fls.taglib.Namespaces;
import io.github.jungm.fls.xml.XmlAttribute;
import io.github.jungm.fls.xml.XmlElement;

/**
 * Resolves component search expressions (Faces 2.3+ and PrimeFaces) statically against the page's component tree:
 * {@code @this @parent @form @namingcontainer @composite @child(n) @next @previous @root @all @none}, PrimeFaces'
 * {@code @id(id)} and {@code @widgetVar(name)}, plain and absolute ids ({@code name}, {@code form:name},
 * {@code :form:name}), and chains such as {@code @form:@child(0)}. Several expressions are separated by spaces or
 * commas.
 */
public final class SearchExpressions {

    /** One step of a chain: a keyword (with its argument) or an id. Offsets are absolute. */
    public record Segment(String text, int start, int end) {
        boolean isKeyword() {
            return text.startsWith("@");
        }

        String keyword() {
            int paren = text.indexOf('(');
            return paren < 0 ? text : text.substring(0, paren);
        }

        String argument() {
            int open = text.indexOf('(');
            int close = text.lastIndexOf(')');
            return open < 0 || close < open ? "" : text.substring(open + 1, close).trim();
        }
    }

    /** A chain of segments; {@code absolute} for ids starting with ':' (searched from the view root). */
    public record Expression(List<Segment> segments, boolean absolute, int start, int end) {
    }

    private static final Set<String> SEARCH_ATTRIBUTES = Set.of("for", "render", "execute", "update", "process");

    /** Attributes that hold search expressions only on some tags. */
    private static final Map<String, Set<String>> TAG_SPECIFIC = Map.of(
            "target", Set.of("defaultCommand", "resetInput", "printer"),
            "scope", Set.of("defaultCommand"),
            "appendTo", Set.of("dialog", "confirmDialog", "overlayPanel", "sidebar", "datePicker", "calendar",
                    "autoComplete", "selectOneMenu", "selectCheckboxMenu", "colorPicker", "menu"),
            "trigger", Set.of("blockUI"),
            "block", Set.of("blockUI"),
            "context", Set.of("focus"));

    private static final Set<String> PRIMEFACES = Set.of("primefaces", "http://primefaces.org/ui");

    private final FacesDocument document;
    private final ComponentModel components;

    public SearchExpressions(ProjectModel project, FacesDocument document) {
        this.document = document;
        this.components = new ComponentModel(project);
    }

    public static boolean isSearchAttribute(XmlElement element, String attribute) {
        if (SEARCH_ATTRIBUTES.contains(attribute)) {
            return true;
        }
        Set<String> tags = TAG_SPECIFIC.get(attribute);
        return tags != null && tags.contains(element.localName()) && PRIMEFACES.contains(element.namespace());
    }

    // ---------------------------------------------------------------- parsing

    /** Splits an attribute value into expressions and their segments. */
    public static List<Expression> parse(String value, int valueStart) {
        List<Expression> expressions = new ArrayList<>();
        int i = 0;
        while (i < value.length()) {
            while (i < value.length() && (Character.isWhitespace(value.charAt(i)) || value.charAt(i) == ',')) {
                i++;
            }
            if (i >= value.length()) {
                break;
            }
            int start = i;
            int depth = 0;
            while (i < value.length()) {
                char c = value.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth = Math.max(0, depth - 1);
                } else if (depth == 0 && (Character.isWhitespace(c) || c == ',')) {
                    break;
                }
                i++;
            }
            expressions.add(chain(value.substring(start, i), valueStart + start));
        }
        return expressions;
    }

    private static Expression chain(String text, int offset) {
        boolean absolute = text.startsWith(":");
        List<Segment> segments = new ArrayList<>();
        int i = absolute ? 1 : 0;
        int start = i;
        int depth = 0;
        for (; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : ':';
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == ':' && depth == 0) {
                if (i > start) {
                    segments.add(new Segment(text.substring(start, i), offset + start, offset + i));
                }
                start = i + 1;
            }
        }
        return new Expression(segments, absolute, offset, offset + text.length());
    }

    /** The expression and the index of the segment containing {@code offset}, or null. */
    public record Hit(Expression expression, int segment) {
    }

    public static Hit at(List<Expression> expressions, int offset) {
        for (Expression expression : expressions) {
            for (int i = 0; i < expression.segments().size(); i++) {
                Segment segment = expression.segments().get(i);
                if (offset >= segment.start() && offset <= segment.end()) {
                    return new Hit(expression, i);
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- component tree

    boolean isComponent(XmlElement element) {
        return components.isComponent(element);
    }

    boolean isNamingContainer(XmlElement element) {
        return components.isNamingContainer(element);
    }

    private XmlElement parentComponent(XmlElement element) {
        for (XmlElement current = element.parent(); current != null; current = current.parent()) {
            if (isComponent(current)) {
                return current;
            }
        }
        return null;
    }

    /** Child components, looking through tag handlers and plain markup between them. */
    private List<XmlElement> childComponents(XmlElement element) {
        List<XmlElement> result = new ArrayList<>();
        for (XmlElement child : element.children()) {
            if (isComponent(child)) {
                result.add(child);
            } else {
                result.addAll(childComponents(child));
            }
        }
        return result;
    }

    private List<XmlElement> rootComponents() {
        List<XmlElement> result = new ArrayList<>();
        for (XmlElement root : document.xml().roots()) {
            if (isComponent(root)) {
                result.add(root);
            } else {
                result.addAll(childComponents(root));
            }
        }
        return result;
    }

    /**
     * The component an attribute belongs to: the element itself, or for behaviors and other tag handlers
     * ({@code f:ajax}, {@code p:ajax}) the component they are attached to.
     */
    XmlElement owner(XmlElement element) {
        return isComponent(element) ? element : parentComponent(element);
    }

    // ---------------------------------------------------------------- resolution

    /** Components the expression resolves to after its first {@code segments + 1} segments. */
    public List<XmlElement> resolve(XmlElement attributeOwner, Expression expression, int lastSegment) {
        XmlElement base = owner(attributeOwner);
        Set<XmlElement> current = new LinkedHashSet<>();
        boolean fromRoot = expression.absolute();
        if (base != null) {
            current.add(base);
        }
        for (int i = 0; i <= lastSegment && i < expression.segments().size(); i++) {
            Segment segment = expression.segments().get(i);
            Set<XmlElement> next = new LinkedHashSet<>();
            if (fromRoot && i == 0) {
                next.addAll(findId(null, segment.text(), true));
            } else if (current.isEmpty() && !segment.isKeyword()) {
                next.addAll(findId(null, segment.text(), false));
            } else {
                for (XmlElement from : current) {
                    next.addAll(step(from, segment, i == 0));
                }
            }
            current = next;
            if (current.isEmpty()) {
                break;
            }
        }
        return List.copyOf(current);
    }

    private List<XmlElement> step(XmlElement from, Segment segment, boolean first) {
        if (!segment.isKeyword()) {
            // the first id is searched relative to the base's naming container, later ones inside the previous match
            return first ? findId(namingContainerOf(from, true), segment.text(), false) : findId(from, segment.text(), false);
        }
        XmlElement single = switch (segment.keyword()) {
            case "@this" -> from;
            case "@parent" -> parentComponent(from);
            case "@form" -> from.localName().equals("form") && isComponent(from) ? from
                    : closest(from, e -> e.localName().equals("form") && isComponent(e));
            case "@namingcontainer" -> namingContainerOf(from, false);
            case "@composite" -> compositeOf(from);
            case "@root" -> document.xml().root();
            case "@next" -> sibling(from, 1);
            case "@previous" -> sibling(from, -1);
            case "@child" -> child(from, segment.argument());
            default -> null;
        };
        if (single != null) {
            return List.of(single);
        }
        return switch (segment.keyword()) {
            case "@id" -> findAllIds(segment.argument());
            case "@widgetVar" -> findAttribute("widgetVar", segment.argument());
            default -> List.of();
        };
    }

    private XmlElement closest(XmlElement from, Predicate<XmlElement> test) {
        for (XmlElement current = from.parent(); current != null; current = current.parent()) {
            if (test.test(current)) {
                return current;
            }
        }
        return null;
    }

    /** The closest naming container; with {@code includeSelf} the element itself when it is one. */
    private XmlElement namingContainerOf(XmlElement from, boolean includeSelf) {
        if (includeSelf && isNamingContainer(from)) {
            return from;
        }
        return closest(from, this::isNamingContainer);
    }

    /** In a composite component's implementation, the cc:implementation element. */
    private XmlElement compositeOf(XmlElement from) {
        return closest(from, e -> e.localName().equals("implementation")
                && Namespaces.COMPOSITE.equals(Namespaces.canonical(e.namespace())));
    }

    private XmlElement sibling(XmlElement from, int direction) {
        XmlElement parent = parentComponent(from);
        List<XmlElement> siblings = parent == null ? rootComponents() : childComponents(parent);
        int index = siblings.indexOf(from);
        int target = index + direction;
        return index < 0 || target < 0 || target >= siblings.size() ? null : siblings.get(target);
    }

    private XmlElement child(XmlElement from, String argument) {
        try {
            int index = Integer.parseInt(argument);
            List<XmlElement> children = childComponents(from);
            return index >= 0 && index < children.size() ? children.get(index) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Components with the id below {@code scope} (the whole page when null). Matches outside nested naming
     * containers come first, as {@code UIComponent.findComponent} would find them; ids in nested containers and,
     * when nothing is found in the scope, anywhere in the page, are returned too, since templates and includes
     * make the real tree unknowable from one file.
     */
    private List<XmlElement> findId(XmlElement scope, String id, boolean absolute) {
        List<XmlElement> direct = new ArrayList<>();
        List<XmlElement> nested = new ArrayList<>();
        List<XmlElement> roots = scope == null ? document.xml().roots() : scope.children();
        for (XmlElement root : roots) {
            collectIds(root, id, false, direct, nested);
        }
        if (!direct.isEmpty()) {
            return direct;
        }
        if (!nested.isEmpty()) {
            return nested;
        }
        return scope == null || absolute ? List.of() : findId(null, id, false);
    }

    private void collectIds(XmlElement element, String id, boolean insideContainer, List<XmlElement> direct,
                            List<XmlElement> nested) {
        if (id.equals(idOf(element))) {
            (insideContainer ? nested : direct).add(element);
        }
        boolean container = insideContainer || isNamingContainer(element);
        for (XmlElement child : element.children()) {
            collectIds(child, id, container, direct, nested);
        }
    }

    private List<XmlElement> findAllIds(String id) {
        List<XmlElement> result = new ArrayList<>();
        for (XmlElement element : document.xml().allElements()) {
            if (id.equals(idOf(element))) {
                result.add(element);
            }
        }
        return result;
    }

    private List<XmlElement> findAttribute(String attribute, String value) {
        List<XmlElement> result = new ArrayList<>();
        for (XmlElement element : document.xml().allElements()) {
            if (value.equals(element.attributeValue(attribute))) {
                result.add(element);
            }
        }
        return result;
    }

    static String idOf(XmlElement element) {
        return ComponentModel.idOf(element);
    }

    static XmlAttribute idAttribute(XmlElement element) {
        return ComponentModel.idAttribute(element);
    }
}
