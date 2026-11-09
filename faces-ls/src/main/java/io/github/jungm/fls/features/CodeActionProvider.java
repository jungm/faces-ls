package io.github.jungm.fls.features;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import io.github.jungm.fls.FacesSettings;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.project.ProjectModel;
import io.github.jungm.fls.taglib.TagLibrary;
import io.github.jungm.fls.xml.XmlElement;

/**
 * Code actions: declare an undeclared namespace prefix that is predefined in the settings, or all of them at once
 * ({@code source.addMissingImports}, which editors can run on save).
 */
public final class CodeActionProvider {
    public static final String ADD_MISSING_NAMESPACES = "source.addMissingImports";

    private final FacesDocument document;
    private final ProjectModel project;
    private final NamespaceDeclarations namespaces;

    public CodeActionProvider(ElAnalyzer analyzer, FacesSettings settings) {
        this.document = analyzer.document();
        this.project = analyzer.project();
        this.namespaces = new NamespaceDeclarations(settings, analyzer.project(), document);
    }

    /**
     * @param start       start offset of the requested range
     * @param end         end offset of the requested range
     * @param diagnostics diagnostics the client sent for the range
     * @param only        requested kinds, or null for all
     */
    public List<CodeAction> actions(int start, int end, List<Diagnostic> diagnostics, List<String> only) {
        List<CodeAction> actions = new ArrayList<>();
        List<TextEdit> all = new ArrayList<>();
        List<String> declared = new ArrayList<>();
        for (Map.Entry<String, List<int[]>> entry : namespaces.undeclaredPrefixes().entrySet()) {
            String prefix = entry.getKey();
            NamespaceDeclarations.Predefined predefined = namespaces.resolve(prefix);
            if (predefined == null) {
                continue;
            }
            TextEdit edit = namespaces.declare(prefix, predefined.uri());
            if (edit == null) {
                continue;
            }
            all.add(edit);
            declared.add(prefix);
            boolean inRange = entry.getValue().stream().anyMatch(r -> r[0] <= end && r[1] >= start);
            if (inRange && wants(only, CodeActionKind.QuickFix)) {
                CodeAction action = new CodeAction("Declare xmlns:" + prefix + "=\"" + predefined.uri() + "\"");
                action.setKind(CodeActionKind.QuickFix);
                action.setEdit(edit(List.of(edit)));
                action.setIsPreferred(true);
                List<Diagnostic> related = diagnostics.stream()
                        .filter(d -> d.getMessage().contains("'" + prefix + "'"))
                        .toList();
                if (!related.isEmpty()) {
                    action.setDiagnostics(related);
                }
                actions.add(action);
            }
        }
        if (wants(only, CodeActionKind.QuickFix)) {
            actions.addAll(addRequiredAttributes(start, end, diagnostics));
        }
        if (!all.isEmpty() && wants(only, ADD_MISSING_NAMESPACES)) {
            CodeAction action = new CodeAction(all.size() == 1 ? "Declare missing namespace " + declared.get(0)
                    : "Declare missing namespaces " + String.join(", ", declared));
            action.setKind(ADD_MISSING_NAMESPACES);
            action.setEdit(edit(all));
            actions.add(action);
        }
        return actions;
    }

    /** Inserts missing required attributes of the elements whose name is in the range, with snippet tab stops. */
    private List<CodeAction> addRequiredAttributes(int start, int end, List<Diagnostic> diagnostics) {
        List<CodeAction> actions = new ArrayList<>();
        for (XmlElement element : document.xml().allElements()) {
            if (element.nameEnd() < start || element.nameStart() > end) {
                continue;
            }
            List<TagLibrary.AttributeDef> missing = RequiredAttributes.missing(project, element);
            if (missing.isEmpty()) {
                continue;
            }
            StringBuilder inserted = new StringBuilder();
            for (TagLibrary.AttributeDef attribute : missing) {
                inserted.append(' ').append(attribute.name()).append("=\"\"");
            }
            String names = String.join("', '", missing.stream().map(TagLibrary.AttributeDef::name).toList());
            CodeAction action = new CodeAction((missing.size() == 1 ? "Add required attribute '" : "Add required attributes '")
                    + names + "'");
            action.setKind(CodeActionKind.QuickFix);
            action.setIsPreferred(true);
            TextEdit edit = new TextEdit(document.lines().range(element.nameEnd(), element.nameEnd()), inserted.toString());
            action.setEdit(edit(List.of(edit)));
            List<Diagnostic> related = diagnostics.stream()
                    .filter(d -> d.getCode() != null && d.getCode().isLeft()
                            && DiagnosticsProvider.MISSING_REQUIRED_ATTRIBUTE.equals(d.getCode().getLeft()))
                    .toList();
            if (!related.isEmpty()) {
                action.setDiagnostics(related);
            }
            actions.add(action);
        }
        return actions;
    }

    private static boolean wants(List<String> only, String kind) {
        if (only == null || only.isEmpty()) {
            return true;
        }
        return only.stream().anyMatch(requested -> kind.equals(requested) || kind.startsWith(requested + "."));
    }

    private WorkspaceEdit edit(List<TextEdit> edits) {
        return new WorkspaceEdit(Map.of(document.uri(), edits));
    }
}
