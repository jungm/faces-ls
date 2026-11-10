package io.github.jungm.fls.features;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.jungm.fls.taglib.Namespaces;
import io.github.jungm.fls.xml.XmlAttribute;
import io.github.jungm.fls.xml.XmlElement;

/**
 * Plain markup elements that become components through an attribute in the {@code jakarta.faces} namespace
 * ({@code <input type="email" jsf:value="#{user.email}">}), and the tag each one is processed as. The mapping is the
 * table of the default {@code TagDecorator} in the Faces specification:
 * <ul>
 * <li>{@code jsf:} attributes are attributes of the target tag; attributes without a namespace are passed through to
 * the rendered markup; attributes of other namespaces stay what they are.</li>
 * <li>Where a selector names an attribute in the {@code jakarta.faces} namespace, the element must carry it; other
 * selectors name a plain attribute with a given value, {@code *} for any value.</li>
 * <li>Rows of the same element are tried in order; elements without a matching row become {@code jsf:element}.</li>
 * </ul>
 * MyFaces and Mojarra both try {@code jsf:outcome} before the plain {@code <button>} row, unlike the order of the
 * specification's table. Mojarra maps {@code <input type="button">} to {@code h:button}; the specification and MyFaces
 * map it to {@code h:commandButton}, as here.
 */
final class PassthroughElements {
    /** The tag a passthrough element is processed as. */
    record Target(String namespace, String localName) {
        String qualifiedName() {
            return Namespaces.DEFAULT_PREFIXES.get(namespace) + ":" + localName;
        }
    }

    /**
     * One row of the table: {@code attribute} is null for a row that always matches; {@code facesAttribute} for an
     * attribute in the {@code jakarta.faces} namespace; {@code value} is null for presence, {@code *} for any value.
     */
    record Rule(String attribute, boolean facesAttribute, String value, Target target) {
        boolean matches(XmlElement element) {
            if (attribute == null) {
                return true;
            }
            for (XmlAttribute candidate : element.attributes()) {
                if (facesAttribute ? isFacesAttribute(element, candidate) && candidate.localName().equals(attribute)
                        : candidate.name().equals(attribute) && valueMatches(candidate.value())) {
                    return true;
                }
            }
            return false;
        }

        private boolean valueMatches(String actual) {
            return value == null || actual != null && (value.equals("*") || value.equals(actual));
        }
    }

    static final Target ELEMENT = new Target(Namespaces.PASSTHROUGH_ELEMENTS, "element");

    /** Set by the decorator from the element name, so pages never write it. */
    static final String ELEMENT_NAME = "elementName";

    private static final Map<String, List<Rule>> RULES = Map.ofEntries(
            Map.entry("a", List.of(
                    faces("action", "commandLink"),
                    faces("actionListener", "commandLink"),
                    faces("value", "outputLink"),
                    faces("outcome", "link"))),
            Map.entry("body", List.of(always("body"))),
            Map.entry("button", List.of(
                    faces("outcome", "button"),
                    always("commandButton"))),
            Map.entry("form", List.of(always("form"))),
            Map.entry("head", List.of(always("head"))),
            Map.entry("img", List.of(always("graphicImage"))),
            Map.entry("input", List.of(
                    type("button", "commandButton"),
                    type("checkbox", "selectBooleanCheckbox"),
                    type("color", "inputText"),
                    type("date", "inputText"),
                    type("datetime", "inputText"),
                    type("datetime-local", "inputText"),
                    type("email", "inputText"),
                    type("month", "inputText"),
                    type("number", "inputText"),
                    type("range", "inputText"),
                    type("search", "inputText"),
                    type("time", "inputText"),
                    type("url", "inputText"),
                    type("week", "inputText"),
                    type("file", "inputFile"),
                    type("hidden", "inputHidden"),
                    type("password", "inputSecret"),
                    type("reset", "commandButton"),
                    type("submit", "commandButton"),
                    type("*", "inputText"))),
            Map.entry("label", List.of(always("outputLabel"))),
            Map.entry("link", List.of(always("outputStylesheet"))),
            Map.entry("script", List.of(always("outputScript"))),
            Map.entry("select", List.of(
                    new Rule("multiple", false, "*", html("selectManyListbox")),
                    always("selectOneListbox"))),
            Map.entry("textarea", List.of(always("inputTextarea"))));

    private PassthroughElements() {
    }

    private static Target html(String localName) {
        return new Target(Namespaces.HTML, localName);
    }

    private static Rule faces(String attribute, String target) {
        return new Rule(attribute, true, null, html(target));
    }

    private static Rule type(String value, String target) {
        return new Rule("type", false, value, html(target));
    }

    private static Rule always(String target) {
        return new Rule(null, false, null, html(target));
    }

    /** Whether the attribute is in the {@code jakarta.faces} namespace ({@code jsf:id}). */
    static boolean isFacesAttribute(XmlElement element, XmlAttribute attribute) {
        return !attribute.prefix().isEmpty() && !attribute.prefix().equals("xmlns")
                && Namespaces.PASSTHROUGH_ELEMENTS.equals(Namespaces.canonical(element.namespaceFor(attribute.prefix())));
    }

    static boolean hasFacesAttribute(XmlElement element) {
        for (XmlAttribute attribute : element.attributes()) {
            if (isFacesAttribute(element, attribute)) {
                return true;
            }
        }
        return false;
    }

    /** Elements without a namespace or in the XHTML namespace: the ones a {@code jsf:} attribute turns into components. */
    static boolean isMarkup(XmlElement element) {
        String namespace = element.namespace();
        return !element.name().isEmpty() && (namespace == null ? element.prefix().isEmpty() : Namespaces.XHTML.equals(namespace));
    }

    /** Markup elements with a {@code jsf:} attribute. */
    static boolean isPassthroughElement(XmlElement element) {
        return isMarkup(element) && hasFacesAttribute(element);
    }

    /** The tag a passthrough element is processed as, or null for anything else. */
    static Target target(XmlElement element) {
        return isPassthroughElement(element) ? targetOf(element) : null;
    }

    /** The tag a markup element is processed as once it has a {@code jsf:} attribute. */
    static Target targetOf(XmlElement element) {
        for (Rule rule : rules(element)) {
            if (rule.matches(element)) {
                return rule.target();
            }
        }
        return ELEMENT;
    }

    /** The rows of the table for the element's name. */
    static List<Rule> rules(XmlElement element) {
        return RULES.getOrDefault(element.localName().toLowerCase(Locale.ROOT), List.of());
    }

    /**
     * The name an attribute has on the component: the local name of a {@code jsf:} attribute, the name itself for
     * other attributes.
     */
    static String componentAttributeName(XmlElement element, XmlAttribute attribute) {
        return isFacesAttribute(element, attribute) ? attribute.localName() : attribute.name();
    }
}
