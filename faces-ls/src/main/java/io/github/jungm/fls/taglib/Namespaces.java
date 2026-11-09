package io.github.jungm.fls.taglib;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Namespace URIs of the standard libraries across Faces versions. The Jakarta Faces 4 URNs are canonical; older
 * {@code xmlns.jcp.org} and {@code java.sun.com} URIs map onto them.
 */
public final class Namespaces {
    public static final String HTML = "jakarta.faces.html";
    public static final String CORE = "jakarta.faces.core";
    public static final String FACELETS = "jakarta.faces.facelets";
    public static final String COMPOSITE = "jakarta.faces.composite";
    public static final String PASSTHROUGH = "jakarta.faces.passthrough";
    public static final String PASSTHROUGH_ELEMENTS = "jakarta.faces";
    public static final String JSTL_CORE = "jakarta.tags.core";
    public static final String JSTL_FUNCTIONS = "jakarta.tags.functions";
    public static final String XHTML = "http://www.w3.org/1999/xhtml";

    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    /** Conventional prefixes, used when suggesting a namespace declaration. */
    public static final Map<String, String> DEFAULT_PREFIXES = Map.of(
            HTML, "h", CORE, "f", FACELETS, "ui", COMPOSITE, "cc", PASSTHROUGH, "pt", PASSTHROUGH_ELEMENTS, "jsf",
            JSTL_CORE, "c", JSTL_FUNCTIONS, "fn");

    static {
        alias(HTML, "http://xmlns.jcp.org/jsf/html", "http://java.sun.com/jsf/html");
        alias(CORE, "http://xmlns.jcp.org/jsf/core", "http://java.sun.com/jsf/core");
        alias(FACELETS, "http://xmlns.jcp.org/jsf/facelets", "http://java.sun.com/jsf/facelets");
        alias(COMPOSITE, "http://xmlns.jcp.org/jsf/composite", "http://java.sun.com/jsf/composite");
        alias(PASSTHROUGH, "http://xmlns.jcp.org/jsf/passthrough");
        alias(PASSTHROUGH_ELEMENTS, "http://xmlns.jcp.org/jsf");
        alias(JSTL_CORE, "http://xmlns.jcp.org/jsp/jstl/core", "http://java.sun.com/jsp/jstl/core",
                "http://java.sun.com/jstl/core");
        alias(JSTL_FUNCTIONS, "http://xmlns.jcp.org/jsp/jstl/functions", "http://java.sun.com/jsp/jstl/functions");
    }

    private static final List<String> COMPOSITE_PREFIXES = List.of(
            "jakarta.faces.composite/", "http://xmlns.jcp.org/jsf/composite/", "http://java.sun.com/jsf/composite/");

    private Namespaces() {
    }

    private static void alias(String canonical, String... others) {
        ALIASES.put(canonical, canonical);
        for (String other : others) {
            ALIASES.put(other, canonical);
        }
    }

    public static String canonical(String uri) {
        if (uri == null) {
            return null;
        }
        String trimmed = uri.trim();
        return ALIASES.getOrDefault(trimmed, trimmed);
    }

    /** The resource library name for a composite component namespace, or null. */
    public static String compositeLibrary(String uri) {
        if (uri == null) {
            return null;
        }
        for (String prefix : COMPOSITE_PREFIXES) {
            if (uri.startsWith(prefix) && uri.length() > prefix.length()) {
                return uri.substring(prefix.length());
            }
        }
        return null;
    }

    public static boolean isStandard(String uri) {
        return ALIASES.containsKey(uri == null ? "" : uri.trim());
    }

    /** All URIs known for the standard libraries, for {@code xmlns} completion. */
    public static Map<String, String> knownUris() {
        return ALIASES;
    }
}
