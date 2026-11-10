package io.github.jungm.fls.features;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.analysis.FacesDocument.ElSite;
import io.github.jungm.fls.taglib.Namespaces;
import io.github.jungm.fls.taglib.TagLibrary;
import io.github.jungm.fls.xml.XmlAttribute;
import io.github.jungm.fls.xml.XmlElement;

/**
 * Problems in a Facelets document: unknown tags and attributes of known libraries (also {@code jsf:} attributes of
 * passthrough elements), missing required attributes, unknown tag libraries, EL syntax errors and unresolved EL names.
 */
public final class DiagnosticsProvider {
    private static final String SOURCE = "faces";
    static final String MISSING_REQUIRED_ATTRIBUTE = "missing-required-attribute";

    private final ElAnalyzer analyzer;
    private final FacesDocument document;

    public DiagnosticsProvider(ElAnalyzer analyzer) {
        this.analyzer = analyzer;
        this.document = analyzer.document();
    }

    public List<Diagnostic> diagnostics() {
        List<Diagnostic> result = new ArrayList<>();
        for (XmlElement element : document.xml().allElements()) {
            checkElement(element, result);
        }
        for (ElSite site : document.elSites()) {
            for (ElAnalyzer.Problem problem : analyzer.check(site)) {
                result.add(diagnostic(problem.message(), problem.start(), problem.end(), switch (problem.severity()) {
                    case ERROR -> DiagnosticSeverity.Error;
                    case WARNING -> DiagnosticSeverity.Warning;
                    case INFO -> DiagnosticSeverity.Information;
                }));
            }
        }
        return result;
    }

    private Diagnostic diagnostic(String message, int start, int end, DiagnosticSeverity severity) {
        Diagnostic diagnostic = new Diagnostic(document.lines().range(start, Math.max(end, start)), message, severity, SOURCE);
        return diagnostic;
    }

    private void checkElement(XmlElement element, List<Diagnostic> result) {
        checkNamespaceDeclarations(element, result);
        if (PassthroughElements.isMarkup(element)) {
            checkPassthroughElement(element, result);
            return;
        }
        if (element.name().isEmpty() || element.prefix().isEmpty() && element.namespace() == null) {
            return;
        }
        String namespace = element.namespace();
        if (namespace == null) {
            result.add(diagnostic("Namespace prefix '" + element.prefix() + "' is not declared", element.nameStart(),
                    element.nameStart() + element.prefix().length(), DiagnosticSeverity.Error));
            return;
        }
        for (XmlAttribute attribute : element.attributes()) {
            if (PassthroughElements.isFacesAttribute(element, attribute)) {
                result.add(diagnostic("Attributes in the jakarta.faces namespace are only allowed on plain HTML elements",
                        attribute.nameStart(), attribute.nameEnd(), DiagnosticSeverity.Warning));
            }
        }
        Tags.Resolved resolved = Tags.resolve(analyzer.project(), element);
        if (resolved.library() == null) {
            return;
        }
        TagLibrary.TagDef tag = resolved.tag();
        if (tag == null) {
            String what = resolved.library().composite() ? "No composite component '" + element.localName() + "' in "
                    + resolved.library().namespace() : "Unknown tag '" + element.localName() + "' in " + resolved.library().namespace();
            result.add(diagnostic(what, element.nameStart(), element.nameEnd(), DiagnosticSeverity.Warning));
            return;
        }
        if (resolved.library().attributesComplete()) {
            for (XmlAttribute attribute : element.attributes()) {
                String name = attribute.name();
                if (name.contains(":") || name.equals("xmlns") || tag.attribute(name) != null) {
                    continue;
                }
                result.add(diagnostic("Unknown attribute '" + name + "' for <" + element.name() + ">", attribute.nameStart(),
                        attribute.nameEnd(), DiagnosticSeverity.Warning));
            }
        }
        checkRequiredAttributes(element, result);
    }

    /** {@code jsf:} attributes of an element such as {@code <input jsf:value="...">}, against the tag it stands for. */
    private void checkPassthroughElement(XmlElement element, List<Diagnostic> result) {
        Tags.Resolved resolved = Tags.resolve(analyzer.project(), element);
        if (resolved.tag() == null) {
            return;
        }
        if (resolved.library().attributesComplete()) {
            for (XmlAttribute attribute : element.attributes()) {
                if (PassthroughElements.isFacesAttribute(element, attribute) && resolved.attribute(element, attribute) == null) {
                    result.add(diagnostic("Unknown attribute '" + attribute.localName() + "' for <" + element.name() + ">, "
                            + "processed as <" + resolved.tagName(element) + ">", attribute.nameStart(), attribute.nameEnd(),
                            DiagnosticSeverity.Warning));
                }
            }
        }
        checkRequiredAttributes(element, result);
    }

    private void checkRequiredAttributes(XmlElement element, List<Diagnostic> result) {
        for (TagLibrary.AttributeDef attribute : RequiredAttributes.missing(analyzer.project(), element)) {
            Diagnostic missing = diagnostic("Missing required attribute '" + attribute.name() + "'", element.nameStart(),
                    element.nameEnd(), DiagnosticSeverity.Error);
            missing.setCode(MISSING_REQUIRED_ATTRIBUTE);
            result.add(missing);
        }
    }

    private void checkNamespaceDeclarations(XmlElement element, List<Diagnostic> result) {
        if (!analyzer.project().javaModelLoaded()) {
            // without the classpath, third-party libraries cannot be told apart from typos
            return;
        }
        for (XmlAttribute attribute : element.attributes()) {
            if (!attribute.name().startsWith("xmlns:") || attribute.value() == null) {
                continue;
            }
            String uri = attribute.value().trim();
            if (uri.isEmpty() || uri.startsWith("http://www.w3.org/") || Namespaces.isStandard(uri)) {
                continue;
            }
            TagLibrary library = analyzer.project().taglibs().library(uri);
            if (library == null) {
                result.add(diagnostic("Unknown tag library '" + uri + "'", attribute.valueStart(), attribute.valueEnd(),
                        DiagnosticSeverity.Information));
            } else if (library.composite() && library.tags().isEmpty()) {
                result.add(diagnostic("No composite components found for '" + uri + "'", attribute.valueStart(),
                        attribute.valueEnd(), DiagnosticSeverity.Warning));
            }
        }
    }
}
