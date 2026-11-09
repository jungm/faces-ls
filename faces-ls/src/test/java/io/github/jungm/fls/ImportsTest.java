package io.github.jungm.fls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Hover;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.JsonParser;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.analysis.Imports;
import io.github.jungm.fls.features.CompletionProvider;
import io.github.jungm.fls.features.DiagnosticsProvider;
import io.github.jungm.fls.features.HoverProvider;
import io.github.jungm.fls.project.ProjectModel;

/** EL through f:importConstants, p:importEnum, p:importConstants, o:importConstants, o:importFunctions and custom tags. */
class ImportsTest {
    @TempDir
    Path workspace;

    FakeJdt jdt;

    private static final String PAGE = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:h="jakarta.faces.html" xmlns:ui="jakarta.faces.facelets"
                  xmlns:p="primefaces" xmlns:o="omnifaces" xmlns:x="http://example.org/ui">
              <p:importEnum type="org.example.Color" var="Colors" allSuffix="ALL"/>
              <p:importConstants type="org.example.Limits"/>
              <o:importConstants type="org.example.Outer$Inner"/>
              <o:importFunctions type="org.example.Fmt"/>
              <x:importEnum type="org.example.Color" var="Shades"/>
              <h:outputText value="#{Colors.RED} #{Colors.ALL_VALUES} #{Limits.MAX} #{Limits.MIN} #{Inner.X} #{Fmt:upper('a')} #{Shades.BLUE}"/>
              <ui:repeat var="c" value="#{Colors.ALL}"><h:outputText value="#{c.label}"/></ui:repeat>
              <h:outputText value="#{Colors.NOPE} #{Colors.helper} #{Fmt:nope(1)} #{Fmt:upper()}"/>
              <h:outputText value="#{Colors.} #{Fmt:}"/>
            </html>
            """;

    @BeforeEach
    void setUp() throws Exception {
        jdt = new FakeJdt(workspace, workspace.resolve("rendezvous"))
                .type("org.example.Color", List.of(), List.of("enum RED", "enum BLUE", "static helper:java.lang.String"),
                        List.of("java.lang.String getLabel()"))
                .type("org.example.Limits", List.of("org.example.BaseLimits"), List.of("static MAX:int"), List.of())
                .type("org.example.BaseLimits", List.of(), List.of("static MIN:int"), List.of())
                .type("org.example.Outer.Inner", List.of(), List.of("static X:java.lang.String"), List.of())
                .type("org.example.Fmt", List.of(), List.of(),
                        List.of("static java.lang.String upper(java.lang.String)", "java.lang.String instanceMethod()"));
    }

    @AfterEach
    void tearDown() throws Exception {
        jdt.close();
    }

    private ElAnalyzer analyzer() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        Path page = workspace.resolve("src/main/webapp/page.xhtml");
        Files.createDirectories(page.getParent());
        Files.writeString(page, PAGE);
        ProjectModel model = new ProjectModel(workspace, jdt.client());
        model.refresh();
        FakeJdt.awaitBeans(model);
        FacesSettings settings = FacesSettings.from(JsonParser.parseString(
                "{\"imports\": {\"http://example.org/ui\": {\"importEnum\": \"enum\"}}}"));
        return new ElAnalyzer(model, new FacesDocument(page.toUri().toString(), PAGE, 1)).withImports(settings.imports());
    }

    /** EL problems on the line that contains {@code text}. */
    private static List<String> elProblems(ElAnalyzer analyzer, String text) {
        int start = PAGE.lastIndexOf('\n', PAGE.indexOf(text)) + 1;
        int end = PAGE.indexOf('\n', start);
        return new DiagnosticsProvider(analyzer).diagnostics().stream()
                .filter(d -> !d.getMessage().startsWith("Unknown tag"))
                .filter(d -> {
                    int offset = analyzer.document().lines().offset(d.getRange().getStart());
                    return offset >= start && offset < end;
                })
                .map(Diagnostic::getMessage).toList();
    }

    private static List<String> labels(ElAnalyzer analyzer, int offset) {
        return new CompletionProvider(analyzer, FacesSettings.defaults()).complete(offset).stream()
                .map(CompletionItem::getLabel).toList();
    }

    @Test
    void resolvesImportedConstantsEnumsAndFunctions() throws Exception {
        ElAnalyzer analyzer = analyzer();
        assertEquals(List.of(), elProblems(analyzer, "#{Colors.RED}"));
        // the elements of the all-values array are the enum
        assertEquals(List.of(), elProblems(analyzer, "#{c.label}"));
    }

    @Test
    void reportsWhatTheImportsDoNotHave() throws Exception {
        List<String> problems = elProblems(analyzer(), "#{Colors.NOPE}");
        assertEquals(4, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("'NOPE'"), problems.toString());
        // p:importEnum exposes only the enum constants
        assertTrue(problems.get(1).contains("'helper'"), problems.toString());
        assertTrue(problems.get(2).contains("'nope'"), problems.toString());
        assertTrue(problems.get(3).contains("takes 1 argument"), problems.toString());
    }

    @Test
    void completesImportedNames() throws Exception {
        ElAnalyzer analyzer = analyzer();
        List<String> members = labels(analyzer, PAGE.indexOf("#{Colors.}") + "#{Colors.".length());
        assertTrue(members.containsAll(List.of("RED", "BLUE", "ALL_VALUES", "ALL")), members.toString());
        assertFalse(members.contains("helper"), members.toString());

        List<String> functions = labels(analyzer, PAGE.indexOf("#{Fmt:}") + "#{Fmt:".length());
        assertEquals(List.of("Fmt:upper"), functions);

        List<String> variables = labels(analyzer, PAGE.indexOf("#{Colors.RED}") + 2);
        assertTrue(variables.containsAll(List.of("Colors", "Limits", "Inner", "Shades", "Fmt:upper")), variables.toString());
    }

    @Test
    void hoversImportedFunctions() throws Exception {
        Hover hover = new HoverProvider(analyzer()).hover(PAGE.indexOf("Fmt:upper('a')") + 5);
        assertNotNull(hover);
        String text = hover.getContents().getRight().getValue();
        assertTrue(text.contains("upper(String)") && text.contains("org.example.Fmt"), text);
    }

    @Test
    void settingsAddAndRemoveImportTags() {
        Imports imports = FacesSettings.from(JsonParser.parseString(
                "{\"imports\": {\"primefaces\": {\"importEnum\": null}, \"http://example.org/ui\": {\"importFunctions\": \"functions\"}}}"))
                .imports();
        var document = io.github.jungm.fls.xml.XmlDocument.parse(
                "<a xmlns:p=\"primefaces\" xmlns:x=\"http://example.org/ui\"><p:importEnum type=\"a.B\"/>"
                        + "<p:importConstants type=\"a.C\"/><x:importFunctions type=\"a.D\"/></a>");
        List<String> found = imports.all(document).stream().map(i -> i.kind() + " " + i.var()).toList();
        assertEquals(List.of("CONSTANTS C", "FUNCTIONS D"), found);
        assertNull(Imports.DEFAULT.functions(document, "D"));
    }
}
