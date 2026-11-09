package io.github.jungm.fls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.features.CodeActionProvider;
import io.github.jungm.fls.features.DiagnosticsProvider;
import io.github.jungm.fls.project.ProjectModel;

class RequiredAttributeTest {
    @TempDir
    Path project;

    private static final String HEAD = """
            <html xmlns:h="jakarta.faces.html" xmlns:f="jakarta.faces.core" xmlns:ui="jakarta.faces.facelets"
                  xmlns:jsf="jakarta.faces" xmlns:pt="jakarta.faces.passthrough">
            """;

    private ElAnalyzer analyzer(String body) throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        String text = HEAD + body + "\n</html>\n";
        Path page = project.resolve("page.xhtml");
        Files.writeString(page, text);
        ProjectModel model = new ProjectModel(project, null);
        model.refresh();
        return new ElAnalyzer(model, new FacesDocument(page.toUri().toString(), text, 1));
    }

    private List<Diagnostic> missing(String body) throws Exception {
        return new DiagnosticsProvider(analyzer(body)).diagnostics().stream()
                .filter(d -> d.getMessage().startsWith("Missing required attribute")).toList();
    }

    @Test
    void missingRequiredAttributeIsAnError() throws Exception {
        List<Diagnostic> diagnostics = missing("<h:message/>\n<ui:param name=\"x\"/>");
        assertEquals(2, diagnostics.size(), diagnostics.toString());
        assertTrue(diagnostics.stream().allMatch(d -> d.getSeverity() == DiagnosticSeverity.Error));
        assertTrue(diagnostics.get(0).getMessage().contains("'for'"));
        assertTrue(diagnostics.get(1).getMessage().contains("'value'"));
    }

    @Test
    void presentAttributesAreAccepted() throws Exception {
        assertTrue(missing("<h:message for=\"name\"/>").isEmpty());
        // set through a nested f:attribute
        assertTrue(missing("<h:message><f:attribute name=\"for\" value=\"name\"/></h:message>").isEmpty());
        // elementName belongs in the passthrough namespace
        assertTrue(missing("<jsf:element pt:elementName=\"section\"/>").isEmpty());
        // ui:repeat works with begin/end and without var
        assertTrue(missing("<ui:repeat begin=\"0\" end=\"3\">x</ui:repeat>").isEmpty());
    }

    @Test
    void quickFixInsertsTheMissingAttributes() throws Exception {
        ElAnalyzer analyzer = analyzer("<ui:param/>");
        String text = analyzer.document().text();
        int at = text.indexOf("ui:param") + 2;
        CodeAction action = new CodeActionProvider(analyzer, FacesSettings.defaults()).actions(at, at, List.of(), null).stream()
                .filter(a -> a.getTitle().startsWith("Add required")).findFirst().orElseThrow();
        assertEquals("Add required attributes 'name', 'value'", action.getTitle());
        TextEdit edit = action.getEdit().getChanges().values().iterator().next().get(0);
        assertEquals(" name=\"\" value=\"\"", edit.getNewText());
        assertEquals(text.indexOf("ui:param") + "ui:param".length(), analyzer.document().lines().offset(edit.getRange().getStart()));
    }
}
