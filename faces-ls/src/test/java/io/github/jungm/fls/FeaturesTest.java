package io.github.jungm.fls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.features.CompletionProvider;
import io.github.jungm.fls.features.DefinitionProvider;
import io.github.jungm.fls.features.DiagnosticsProvider;
import io.github.jungm.fls.features.HoverProvider;
import io.github.jungm.fls.project.ProjectModel;
import io.github.jungm.fls.taglib.TagLibrary;
import io.github.jungm.fls.taglib.TaglibRegistry;

/** Features that work without jdtls: taglibs, composite components, resource bundles. */
class FeaturesTest {
    @TempDir
    Path project;

    private static final String PAGE = """
            <ui:composition xmlns="http://www.w3.org/1999/xhtml"
                            xmlns:h="jakarta.faces.html"
                            xmlns:ui="jakarta.faces.facelets"
                            xmlns:comp="jakarta.faces.composite/components">
                <h:form id="form">
                    <h:inputT
                    <h:outputText value="#{msg['wel']}" bogus="x"/>
                    <h:outputLabel/>
                    <comp:card />
                    <h:unknownTag/>
                </h:form>
            </ui:composition>
            """;

    private ElAnalyzer analyzer(String text) throws Exception {
        Files.createDirectories(project.resolve("src/main/webapp/WEB-INF"));
        Files.createDirectories(project.resolve("src/main/webapp/resources/components"));
        Files.createDirectories(project.resolve("src/main/resources/demo"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("src/main/webapp/WEB-INF/faces-config.xml"), """
                <faces-config><application><resource-bundle>
                  <base-name>demo.messages</base-name><var>msg</var>
                </resource-bundle></application></faces-config>""");
        Files.writeString(project.resolve("src/main/resources/demo/messages.properties"), "welcome=Hello\nbye=Bye\n");
        Files.writeString(project.resolve("src/main/webapp/resources/components/card.xhtml"), """
                <ui:component xmlns:ui="jakarta.faces.facelets" xmlns:cc="jakarta.faces.composite">
                  <cc:interface><cc:attribute name="title" required="true"/></cc:interface>
                  <cc:implementation>#{cc.attrs.title}</cc:implementation>
                </ui:component>""");
        Path page = project.resolve("src/main/webapp/page.xhtml");
        Files.writeString(page, text);
        ProjectModel model = new ProjectModel(project, null);
        model.refresh();
        return new ElAnalyzer(model, new FacesDocument(page.toUri().toString(), text, 1));
    }

    @Test
    void bundledTaglibsAreLoaded() {
        assertTrue(TaglibRegistry.standardLibraries().size() >= 8);
        TagLibrary html = new TaglibRegistry().library("http://xmlns.jcp.org/jsf/html");
        assertNotNull(html);
        assertNotNull(html.tags().get("inputText").attribute("value"));
        TagLibrary functions = new TaglibRegistry().library("jakarta.tags.functions");
        assertNotNull(functions.functions().get("length"));
    }

    @Test
    void completesTagNames() throws Exception {
        ElAnalyzer analyzer = analyzer(PAGE);
        int offset = PAGE.indexOf("<h:inputT") + "<h:inputT".length();
        List<CompletionItem> items = new CompletionProvider(analyzer, io.github.jungm.fls.FacesSettings.defaults()).complete(offset);
        assertTrue(items.stream().anyMatch(i -> i.getLabel().equals("h:inputText")), labels(items));
        int componentOffset = PAGE.indexOf("<comp:card") + "<comp:c".length();
        items = new CompletionProvider(analyzer, io.github.jungm.fls.FacesSettings.defaults()).complete(componentOffset);
        assertTrue(items.stream().anyMatch(i -> i.getLabel().equals("comp:card")), labels(items));
    }

    @Test
    void completesAttributes() throws Exception {
        ElAnalyzer analyzer = analyzer(PAGE);
        int offset = PAGE.indexOf("<h:outputLabel") + "<h:outputLabel ".length() - 1;
        String text = PAGE.substring(0, offset) + " " + PAGE.substring(offset);
        analyzer = analyzer(text);
        List<CompletionItem> items = new CompletionProvider(analyzer, io.github.jungm.fls.FacesSettings.defaults()).complete(offset + 1);
        assertTrue(items.stream().anyMatch(i -> i.getLabel().equals("for")), labels(items));
    }

    @Test
    void completesBundleKeys() throws Exception {
        ElAnalyzer analyzer = analyzer(PAGE);
        int offset = PAGE.indexOf("msg['wel") + "msg['wel".length();
        List<CompletionItem> items = new CompletionProvider(analyzer, io.github.jungm.fls.FacesSettings.defaults()).complete(offset);
        assertTrue(items.stream().anyMatch(i -> i.getLabel().equals("welcome")), labels(items));
    }

    @Test
    void completesVariables() throws Exception {
        String text = PAGE.replace("#{msg['wel']}", "#{m}");
        ElAnalyzer analyzer = analyzer(text);
        int offset = text.indexOf("#{m}") + 3;
        List<CompletionItem> items = new CompletionProvider(analyzer, io.github.jungm.fls.FacesSettings.defaults()).complete(offset);
        assertTrue(items.stream().anyMatch(i -> i.getLabel().equals("msg")), labels(items));
        assertTrue(items.stream().anyMatch(i -> i.getLabel().equals("facesContext")), labels(items));
    }

    @Test
    void reportsMarkupProblems() throws Exception {
        ElAnalyzer analyzer = analyzer(PAGE);
        List<Diagnostic> diagnostics = new DiagnosticsProvider(analyzer).diagnostics();
        List<String> messages = diagnostics.stream().map(Diagnostic::getMessage).toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("Unknown attribute 'bogus'")), messages.toString());
        assertTrue(messages.stream().anyMatch(m -> m.contains("Unknown tag 'unknownTag'")), messages.toString());
        assertTrue(messages.stream().anyMatch(m -> m.contains("Missing required attribute 'title'")), messages.toString());
        assertTrue(messages.stream().anyMatch(m -> m.contains("Key 'wel' not found")), messages.toString());
    }

    @Test
    void hoversTagDocumentation() throws Exception {
        ElAnalyzer analyzer = analyzer(PAGE);
        Hover hover = new HoverProvider(analyzer).hover(PAGE.indexOf("h:outputText") + 3);
        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("outputText"));
        Hover key = new HoverProvider(analyzer(PAGE.replace("'wel'", "'welcome'")))
                .hover(PAGE.indexOf("'wel'") + 2);
        assertNotNull(key);
        assertTrue(key.getContents().getRight().getValue().contains("Hello"), key.getContents().getRight().getValue());
    }

    @Test
    void jumpsToTheTaglibDefinitionOfStandardTagsAndAttributes() throws Exception {
        String text = PAGE.replace("<h:outputLabel/>", "<h:outputLabel for=\"x\"/>");
        ElAnalyzer analyzer = analyzer(text);
        DefinitionProvider definitions = new DefinitionProvider(analyzer, uri -> null);

        List<Location> tag = definitions.definition(text.indexOf("h:outputLabel") + 4);
        assertEquals(1, tag.size());
        assertTrue(tag.get(0).getUri().endsWith("taglibs/faces.html.taglib.xml"), tag.toString());
        assertEquals("outputLabel", textAt(tag.get(0)));

        List<Location> attribute = definitions.definition(text.indexOf("for=\"x\"") + 1);
        assertEquals("for", textAt(attribute.get(0)));
        // the attribute of this tag, not the first "for" in the file
        assertTrue(attribute.get(0).getRange().getStart().getLine() > tag.get(0).getRange().getStart().getLine());
    }

    private static String textAt(Location location) throws Exception {
        String content = Files.readString(Path.of(java.net.URI.create(location.getUri())));
        io.github.jungm.fls.analysis.LineIndex lines = new io.github.jungm.fls.analysis.LineIndex(content);
        return content.substring(lines.offset(location.getRange().getStart()), lines.offset(location.getRange().getEnd()));
    }

    private static String labels(List<CompletionItem> items) {
        return items.stream().map(CompletionItem::getLabel).limit(40).toList().toString() + " (" + items.size() + ")";
    }

    @Test
    void lineIndexRoundTrips() {
        io.github.jungm.fls.analysis.LineIndex index = new io.github.jungm.fls.analysis.LineIndex("ab\ncd\n\nef");
        assertEquals(1, index.position(4).getLine());
        assertEquals(1, index.position(4).getCharacter());
        assertEquals(4, index.offset(index.position(4)));
        assertEquals(8, index.offset(new org.eclipse.lsp4j.Position(3, 1)));
    }
}
