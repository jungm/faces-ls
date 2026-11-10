package io.github.jungm.fls.analysis;

import io.github.jungm.fls.taglib.Namespaces;
import io.github.jungm.fls.xml.XmlElement;

/**
 * Facelets ({@code ui:}) tags by their role.
 */
public final class FaceletsTags {
    private FaceletsTags() {
    }

    /** Whether the element is the Facelets tag {@code ui:<localName>}, under whatever prefix. */
    public static boolean is(XmlElement element, String localName) {
        return element.localName().equals(localName) && Namespaces.FACELETS.equals(Namespaces.canonical(element.namespace()));
    }

    /**
     * The attribute through which a Facelets tag refers to another view: {@code src} of {@code ui:include},
     * {@code template} of {@code ui:composition} and {@code ui:decorate}; null for other tags.
     */
    public static String viewAttribute(XmlElement element) {
        if (!Namespaces.FACELETS.equals(Namespaces.canonical(element.namespace()))) {
            return null;
        }
        return switch (element.localName()) {
            case "include" -> "src";
            case "composition", "decorate" -> "template";
            default -> null;
        };
    }

    /** Whether the attribute's value is the path of another view (see {@link #viewAttribute}). */
    public static boolean isViewReference(XmlElement element, String attributeName) {
        return attributeName.equals(viewAttribute(element));
    }
}
