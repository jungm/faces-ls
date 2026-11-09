package io.github.jungm.fls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.features.CodeActionProvider;
import io.github.jungm.fls.features.CompletionProvider;
import io.github.jungm.fls.project.ProjectModel;

import com.google.gson.JsonParser;

/** Predefined namespace prefixes: completion and code actions declare them on the root element. */
class NamespaceTest {
    @TempDir
    Path project;

    private static final String MULTILINE = """
            <ui:composition xmlns="http://www.w3.org/1999/xhtml"
                            xmlns:h="jakarta.faces.html"
                            xmlns:ui="jakarta.faces.facelets">
                <p:inpu
            </ui:composition>
            """;

    private ElAnalyzer analyzer(String text) throws Exception {
        Files.createDirectories(project.resolve("src/main/webapp"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Path jar = project.resolve("primefaces.jar");
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("META-INF/primefaces.urn.taglib.xml"));
            zip.write("""
                    <facelet-taglib><namespace>primefaces</namespace>
                      <tag><tag-name>inputText</tag-name><attribute><name>value</name></attribute></tag>
                      <tag><tag-name>button</tag-name><attribute><name>value</name></attribute></tag>
                    </facelet-taglib>""".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path page = project.resolve("src/main/webapp/page.xhtml");
        Files.writeString(page, text);
        ProjectModel model = new ProjectModel(project, null);
        model.refresh();
        model.useClasspath(List.of(jar));
        return new ElAnalyzer(model, new FacesDocument(page.toUri().toString(), text, 1));
    }

    private static List<CompletionItem> complete(ElAnalyzer analyzer, FacesSettings settings, int offset) {
        return new CompletionProvider(analyzer, settings).complete(offset);
    }

    @Test
    void completingAnUndeclaredPredefinedPrefixDeclaresIt() throws Exception {
        ElAnalyzer analyzer = analyzer(MULTILINE);
        int offset = MULTILINE.indexOf("<p:inpu") + "<p:inpu".length();
        CompletionItem item = complete(analyzer, FacesSettings.defaults(), offset).stream()
                .filter(i -> i.getLabel().equals("p:inputText")).findFirst().orElseThrow();
        TextEdit declaration = item.getAdditionalTextEdits().get(0);
        // after the last xmlns attribute, on its own line, aligned with it
        assertEquals("\n                xmlns:p=\"primefaces\"", declaration.getNewText());
        assertEquals(2, declaration.getRange().getStart().getLine());
        assertEquals(MULTILINE.split("\n")[2].indexOf(">"), declaration.getRange().getStart().getCharacter());
    }

    @Test
    void singleLineRootKeepsDeclarationsOnOneLine() throws Exception {
        String text = "<html xmlns:h=\"jakarta.faces.html\">\n  <p:but\n</html>\n";
        ElAnalyzer analyzer = analyzer(text);
        CompletionItem item = complete(analyzer, FacesSettings.defaults(), text.indexOf("<p:but") + 6).stream()
                .filter(i -> i.getLabel().equals("p:button")).findFirst().orElseThrow();
        assertEquals(" xmlns:p=\"primefaces\"", item.getAdditionalTextEdits().get(0).getNewText());
    }

    @Test
    void settingsAddAndRemovePrefixes() throws Exception {
        ElAnalyzer analyzer = analyzer(MULTILINE.replace("<p:inpu", "<prime:inpu"));
        int offset = MULTILINE.indexOf("<p:inpu") + "<prime:inpu".length();
        FacesSettings custom = FacesSettings.from(JsonParser.parseString("{\"namespaces\": {\"prime\": \"primefaces\", \"p\": \"\"}}"));
        assertTrue(complete(analyzer, custom, offset).stream().anyMatch(i -> i.getLabel().equals("prime:inputText")));
        assertFalse(custom.namespaces().containsKey("p"));
    }

    @Test
    void libraryDeclaredUnderAnotherPrefixIsNotOfferedAgain() throws Exception {
        String text = MULTILINE.replace("xmlns:ui=\"jakarta.faces.facelets\"", "xmlns:ui=\"jakarta.faces.facelets\" xmlns:x=\"primefaces\"");
        ElAnalyzer analyzer = analyzer(text);
        int offset = text.indexOf("<p:inpu") + "<p:inpu".length();
        assertTrue(complete(analyzer, FacesSettings.defaults(), offset).stream().noneMatch(i -> i.getLabel().startsWith("p:")));
    }

    @Test
    void jumpsToTheTagInAJarTaglib() throws Exception {
        String text = "<html xmlns:p=\"primefaces\">\n  <p:button value=\"x\"/>\n</html>\n";
        ElAnalyzer analyzer = analyzer(text);
        List<org.eclipse.lsp4j.Location> locations = new io.github.jungm.fls.features.DefinitionProvider(analyzer, uri -> null)
                .definition(text.indexOf("p:button") + 3);
        assertEquals(1, locations.size());
        assertTrue(locations.get(0).getUri().endsWith("META-INF/primefaces.urn.taglib.xml"), locations.toString());
        assertEquals(2, locations.get(0).getRange().getStart().getLine());
    }

    @Test
    void codeActionsDeclareMissingNamespaces() throws Exception {
        String text = """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:h="jakarta.faces.html">
                  <p:button value="#{fn:length('abc')}"/>
                  <x:unknown/>
                </html>
                """;
        ElAnalyzer analyzer = analyzer(text);
        int at = text.indexOf("p:button");
        List<CodeAction> actions = new CodeActionProvider(analyzer, FacesSettings.defaults()).actions(at, at, List.of(), null);
        List<String> titles = actions.stream().map(CodeAction::getTitle).toList();
        assertTrue(titles.contains("Declare xmlns:p=\"primefaces\""), titles.toString());
        CodeAction all = actions.stream().filter(a -> CodeActionProvider.ADD_MISSING_NAMESPACES.equals(a.getKind()))
                .findFirst().orElseThrow();
        List<TextEdit> edits = all.getEdit().getChanges().values().iterator().next();
        List<String> inserted = edits.stream().map(TextEdit::getNewText).map(String::trim).toList();
        assertEquals(List.of("xmlns:p=\"primefaces\"", "xmlns:fn=\"jakarta.tags.functions\""), inserted);

        List<CodeAction> onSave = new CodeActionProvider(analyzer, FacesSettings.defaults())
                .actions(0, 0, List.of(), List.of("source"));
        assertEquals(1, onSave.size());
    }
}
