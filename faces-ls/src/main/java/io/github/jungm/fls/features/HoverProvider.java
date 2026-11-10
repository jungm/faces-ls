package io.github.jungm.fls.features;

import java.util.List;

import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.ElType;
import io.github.jungm.fls.analysis.ElVariable;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.analysis.FacesDocument.ElSite;
import io.github.jungm.fls.analysis.Imports;
import io.github.jungm.fls.index.ClientIds.ClientId;
import io.github.jungm.fls.index.FaceletsIndex;
import io.github.jungm.fls.java.TypeService;
import io.github.jungm.fls.project.PropertiesFile;
import io.github.jungm.fls.taglib.TagLibrary;
import io.github.jungm.fls.xml.XmlElement;

/**
 * Hover documentation for tags, attributes and EL names.
 */
public final class HoverProvider {
    private final ElAnalyzer analyzer;
    private final FacesDocument document;

    public HoverProvider(ElAnalyzer analyzer) {
        this.analyzer = analyzer;
        this.document = analyzer.document();
    }

    public Hover hover(int offset) {
        ElSite site = document.elAt(offset);
        if (site != null) {
            return elHover(site, offset);
        }
        Cursor cursor = Cursor.on(document, offset);
        switch (cursor.kind()) {
            case TAG_NAME, CLOSE_TAG_NAME -> {
                Tags.Resolved resolved = Tags.resolve(analyzer.project(), cursor.element());
                if (resolved.tag() == null) {
                    return null;
                }
                StringBuilder text = new StringBuilder("**").append(cursor.element().name()).append("**");
                if (resolved.passthrough() != null) {
                    text.append(" — passthrough element, processed as `").append(resolved.tagName(cursor.element()))
                            .append("` (").append(resolved.library().namespace()).append(')');
                } else {
                    text.append(resolved.library().composite() ? " — composite component" : " — " + resolved.library().namespace());
                }
                text.append("\n\n");
                if (resolved.tag().description() != null) {
                    text.append(resolved.tag().description()).append("\n\n");
                }
                if (resolved.library().composite() && !resolved.tag().attributes().isEmpty()) {
                    text.append("Attributes: ");
                    resolved.tag().attributes().forEach(a -> text.append('`').append(a.name()).append(a.required() ? "*" : "").append("` "));
                }
                int start = cursor.kind() == Cursor.Kind.TAG_NAME ? cursor.element().nameStart() : cursor.element().closeNameStart();
                return hover(text.toString(), start, start + cursor.element().name().length());
            }
            case ATTRIBUTE_NAME -> {
                Tags.Resolved resolved = Tags.resolve(analyzer.project(), cursor.element());
                if (resolved.tag() == null) {
                    return null;
                }
                TagLibrary.AttributeDef attribute = resolved.attribute(cursor.element(), cursor.attribute());
                if (attribute == null) {
                    return null;
                }
                String text = "**" + attribute.name() + "** — attribute of `" + resolved.tagName(cursor.element()) + "`\n\n"
                        + CompletionProvider.attributeDoc(attribute);
                return hover(text, cursor.attribute().nameStart(), cursor.attribute().nameEnd());
            }
            case ATTRIBUTE_VALUE -> {
                return searchExpressionHover(cursor, offset);
            }
            default -> {
                return null;
            }
        }
    }

    /** What the search expression segment under the cursor resolves to. */
    private Hover searchExpressionHover(Cursor cursor, int offset) {
        if (!SearchExpressions.isSearchAttribute(cursor.element(),
                PassthroughElements.componentAttributeName(cursor.element(), cursor.attribute()))) {
            return null;
        }
        SearchExpressions.Hit hit = SearchExpressions.at(
                SearchExpressions.parse(cursor.attribute().value(), cursor.attribute().valueStart()), offset);
        if (hit == null) {
            return null;
        }
        SearchExpressions.Segment segment = hit.expression().segments().get(hit.segment());
        String keyword = segment.isKeyword() ? segment.keyword() : null;
        if ("@all".equals(keyword) || "@none".equals(keyword)) {
            return hover("`" + keyword + "`: " + (keyword.equals("@all") ? "the whole view" : "no component"),
                    segment.start(), segment.end());
        }
        String expression = document.text().substring(hit.expression().start(), segment.end());
        if (hit.expression().absolute()) {
            String clientId = ClientIdLookup.clientId(hit.expression(), hit.segment());
            List<ClientId> found = clientId == null ? List.of() : ClientIdLookup.find(analyzer, clientId);
            if (!found.isEmpty()) {
                StringBuilder text = new StringBuilder("`").append(expression).append("` → ");
                for (int i = 0; i < found.size() && i < 5; i++) {
                    ClientId target = found.get(i);
                    FaceletsIndex.Entry entry = analyzer.index().entry(target.fileUri());
                    text.append(i == 0 ? "" : ", ").append("`<").append(target.tag()).append(">` in ")
                            .append(entry.webPath() != null ? entry.webPath() : target.fileUri()).append(" (line ")
                            .append(entry.lines().position(target.start()).getLine() + 1).append(')');
                }
                return hover(text.toString(), segment.start(), segment.end());
            }
        }
        List<XmlElement> targets = new SearchExpressions(analyzer.project(), document)
                .resolve(cursor.element(), hit.expression(), hit.segment());
        StringBuilder text = new StringBuilder("`").append(expression).append("` → ");
        if (targets.isEmpty()) {
            text.append("no component in this page");
        } else {
            for (int i = 0; i < targets.size() && i < 5; i++) {
                XmlElement target = targets.get(i);
                String id = SearchExpressions.idOf(target);
                text.append(i == 0 ? "" : ", ").append("`<").append(target.name())
                        .append(id == null ? "" : " id=\"" + id + "\"").append(">` (line ")
                        .append(document.lines().position(target.start()).getLine() + 1).append(')');
            }
            if (targets.size() > 5) {
                text.append(", … (").append(targets.size()).append(" components)");
            }
        }
        return hover(text.toString(), segment.start(), segment.end());
    }

    private Hover hover(String markdown, int start, int end) {
        Hover hover = new Hover(new MarkupContent(MarkupKind.MARKDOWN, markdown));
        hover.setRange(document.lines().range(start, end));
        return hover;
    }

    private Hover elHover(ElSite site, int offset) {
        ElLookup.Hit hit = ElLookup.find(analyzer, site, offset);
        if (hit == null) {
            return null;
        }
        switch (hit.kind()) {
            case VARIABLE -> {
                ElVariable variable = hit.variable();
                if (variable == null) {
                    return null;
                }
                StringBuilder text = new StringBuilder("```java\n").append(typeLine(variable.type())).append(' ')
                        .append(variable.name()).append("\n```\n").append(variable.detail());
                if (variable.type() instanceof ElType.Java java && variable.kind() == ElVariable.Kind.BEAN) {
                    String doc = analyzer.types().javadoc(java.type().name(), "type", null, -1);
                    if (doc != null) {
                        text.append("\n\n---\n").append(doc);
                    }
                }
                return hover(text.toString(), hit.start(), hit.end());
            }
            case MEMBER, KEY -> {
                ElLookup.MemberTarget target = ElLookup.resolveMember(analyzer, hit.base(), hit.name(), hit.arguments());
                if (target == null) {
                    return null;
                }
                return hover(memberText(target, hit.name()), hit.start(), hit.end());
            }
            case FUNCTION -> {
                var namespace = site.element() == null ? null : site.element().namespaceFor(hit.function().prefix());
                Imports.Import imported = namespace == null ? analyzer.functionImport(hit.function().prefix()) : null;
                if (imported != null) {
                    TypeService.MethodInfo method = ElAnalyzer.select(analyzer.importedFunctions(imported).stream()
                            .filter(m -> m.name().equals(hit.name())).toList(), hit.arguments());
                    if (method == null) {
                        return null;
                    }
                    StringBuilder text = new StringBuilder("```java\nstatic ").append(method.returnType().display()).append(' ')
                            .append(method.signature()).append("\n```\nImported from `").append(imported.type())
                            .append("` by `").append(imported.element().name()).append('`');
                    String doc = analyzer.types().javadoc(method.declaringType(), "method", method.name(), method.parameters().size());
                    if (doc != null) {
                        text.append("\n\n---\n").append(doc);
                    }
                    return hover(text.toString(), hit.start(), hit.end());
                }
                TagLibrary library = namespace == null ? null : analyzer.project().taglibs().library(namespace);
                TagLibrary.FunctionDef function = library == null ? null : library.functions().get(hit.name());
                if (function == null) {
                    return null;
                }
                String text = "```java\n" + function.signature() + "\n```\n"
                        + (function.description() == null ? "" : function.description())
                        + (function.functionClass() == null ? "" : "\n\nImplemented by `" + function.functionClass() + "`");
                return hover(text, hit.start(), hit.end());
            }
            default -> {
                return null;
            }
        }
    }

    private static String typeLine(ElType type) {
        return type instanceof ElType.Unknown ? "Object" : type.display();
    }

    private String memberText(ElLookup.MemberTarget target, String name) {
        TypeService types = analyzer.types();
        if (target.property() != null) {
            TypeService.Property property = target.property();
            StringBuilder text = new StringBuilder("```java\n").append(property.type().display()).append(' ').append(property.name())
                    .append("\n```\nProperty of `").append(simple(property.declaringType())).append('`');
            text.append(property.readable() ? (property.setter() != null ? " (read/write)" : " (read-only)") : " (write-only)");
            TypeService.MethodInfo accessor = property.getter() != null ? property.getter() : property.setter();
            String doc = types.javadoc(accessor.declaringType(), "method", accessor.name(), accessor.parameters().size());
            if (doc != null) {
                text.append("\n\n---\n").append(doc);
            }
            return text.toString();
        }
        if (target.method() != null) {
            TypeService.MethodInfo method = target.method();
            StringBuilder text = new StringBuilder("```java\n").append(method.returnType().display()).append(' ')
                    .append(method.signature()).append("\n```\nMethod of `").append(simple(method.declaringType())).append('`');
            String doc = types.javadoc(method.declaringType(), "method", method.name(), method.parameters().size());
            if (doc != null) {
                text.append("\n\n---\n").append(doc);
            }
            return text.toString();
        }
        if (target.field() != null) {
            return "```java\nstatic " + target.field().type().display() + " " + target.field().name() + "\n```\nConstant of `"
                    + simple(target.field().declaringType()) + "`";
        }
        if (target.compositeAttribute() != null) {
            return "**" + target.compositeAttribute().name() + "** — composite component attribute\n\n"
                    + CompletionProvider.attributeDoc(target.compositeAttribute());
        }
        if (target.bundleBaseName() != null) {
            PropertiesFile file = analyzer.project().bundle(target.bundleBaseName());
            PropertiesFile.Entry entry = file == null ? null : file.entries().get(name);
            if (entry == null) {
                return "Key `" + name + "` of resource bundle `" + target.bundleBaseName() + "` (not found)";
            }
            return "`" + name + "` = " + entry.value() + "\n\nResource bundle `" + target.bundleBaseName() + "`";
        }
        if (target.compositeContract() != null) {
            return "Attributes of composite component `" + target.compositeContract().name() + "`";
        }
        return null;
    }

    private static String simple(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }
}
