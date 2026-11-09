package io.github.jungm.fls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.features.ComponentModel;
import io.github.jungm.fls.features.CompletionProvider;
import io.github.jungm.fls.features.DefinitionProvider;
import io.github.jungm.fls.features.UsageProvider;
import io.github.jungm.fls.index.ClientIds;
import io.github.jungm.fls.index.FaceletsIndex;
import io.github.jungm.fls.project.ProjectModel;

/** Workspace index: usage tree across modules and client ids through templates, includes and composites. */
class IndexTest {
    @TempDir
    Path workspace;

    Path page;
    Path layout;
    Path angaben;
    Path person;
    FaceletsIndex index;

    private static final String NS = " " + """
             xmlns="http://www.w3.org/1999/xhtml" xmlns:h="jakarta.faces.html" xmlns:ui="jakarta.faces.facelets"
             xmlns:cc="jakarta.faces.composite" xmlns:comp="jakarta.faces.composite/comp\"""";

    private Path write(String relative, String text) throws Exception {
        Path file = workspace.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    @BeforeEach
    void setUp() throws Exception {
        write("web/pom.xml", "<project/>");
        write("shared/pom.xml", "<project/>");
        page = write("web/src/main/webapp/index.xhtml", "<ui:composition" + NS + " template=\"/WEB-INF/layout.xhtml\">\n"
                + "  <ui:define name=\"content\">\n"
                + "    <h:form id=\"f\"><ui:include src=\"/WEB-INF/angaben.xhtml\"/></h:form>\n"
                + "  </ui:define>\n</ui:composition>\n");
        layout = write("web/src/main/webapp/WEB-INF/layout.xhtml", "<html" + NS + ">\n<h:body>\n"
                + "  <h:form id=\"header\"><h:outputText id=\"title\" value=\"x\"/></h:form>\n"
                + "  <div><ui:insert name=\"content\"/></div>\n</h:body>\n</html>\n");
        angaben = write("web/src/main/webapp/WEB-INF/angaben.xhtml", "<ui:composition" + NS + ">\n"
                + "  <comp:person id=\"p1\"/>\n"
                + "  <comp:person/>\n"
                + "  <ui:repeat var=\"x\" value=\"#{bean.items}\"><h:inputText id=\"rowInput\"/></ui:repeat>\n"
                + "  <h:commandButton id=\"go\" update=\":\"/>\n"
                + "</ui:composition>\n");
        person = write("shared/src/main/resources/META-INF/resources/comp/person.xhtml", "<ui:component" + NS + ">\n"
                + "  <cc:interface/>\n"
                + "  <cc:implementation><h:inputText id=\"street\"/><h:message for=\":f:p1:street\"/></cc:implementation>\n"
                + "</ui:component>\n");
        index = new FaceletsIndex(List.of(workspace), () -> { });
        index.rebuildNow();
    }

    private String uri(Path file) {
        return file.toUri().toString();
    }

    private ElAnalyzer analyzer(Path file) throws Exception {
        ProjectModel model = new ProjectModel(file.getParent(), null);
        model.refresh();
        String text = Files.readString(file);
        return new ElAnalyzer(model, new FacesDocument(uri(file), text, 1)).withIndex(index.snapshot());
    }

    @Test
    void buildsTheUsageTreeAcrossModules() {
        FaceletsIndex.Snapshot snapshot = index.snapshot();
        assertEquals(Set.of(uri(page)), snapshot.pagesContaining(uri(person)));
        assertEquals(List.of(uri(page)), List.copyOf(snapshot.pagesContaining(uri(angaben))));

        List<CallHierarchyIncomingCall> callers = new UsageProvider(snapshot).incoming(uri(person));
        assertEquals(1, callers.size());
        assertEquals("/WEB-INF/angaben.xhtml", callers.get(0).getFrom().getName());
        assertEquals("include", callers.get(0).getFrom().getDetail());
        assertEquals(2, callers.get(0).getFromRanges().size());
        assertEquals("/index.xhtml", new UsageProvider(snapshot).incoming(uri(angaben)).get(0).getFrom().getName());
        // the template is used by the page as its template
        assertEquals("page", new UsageProvider(snapshot).incoming(uri(layout)).get(0).getFrom().getDetail());
        assertTrue(new UsageProvider(snapshot).codeLenses(uri(person)).get(0).getCommand().getTitle()
                .equals("used in 1 file · 1 page"));
    }

    @Test
    void computesStableClientIdsThroughTemplatesIncludesAndComposites() throws Exception {
        ElAnalyzer analyzer = analyzer(person);
        Map<String, List<ClientIds.ClientId>> ids = ClientIds.forFile(index.snapshot(), new ComponentModel(analyzer.project()),
                uri(person));
        assertTrue(ids.containsKey("header:title"), ids.keySet().toString());
        assertTrue(ids.containsKey("f:p1:street"), ids.keySet().toString());
        assertTrue(ids.containsKey("f:go"), ids.keySet().toString());
        // inside the composite without id and inside ui:repeat, client ids are generated or per row
        assertFalse(ids.keySet().stream().anyMatch(id -> id.contains("rowInput")), ids.keySet().toString());
        assertEquals(1, ids.get("f:p1:street").size());
        assertEquals(uri(person), ids.get("f:p1:street").get(0).fileUri());
    }

    @Test
    void navigatesToIncludesInOtherModules() throws Exception {
        Path debug = write("shared/src/main/resources/META-INF/resources/shared-debug/debug.xhtml",
                "<ui:composition" + NS + "><ui:include src=\"panel.xhtml\"/></ui:composition>\n");
        Path panel = write("shared/src/main/resources/META-INF/resources/shared-debug/panel.xhtml",
                "<ui:composition" + NS + "/>\n");
        Path host = write("web/src/main/webapp/host.xhtml",
                "<ui:composition" + NS + "><ui:include src=\"/shared-debug/debug.xhtml\"/></ui:composition>\n");
        index.rebuildNow();

        ProjectModel web = new ProjectModel(workspace.resolve("web"), null);
        web.refresh();
        String text = Files.readString(host);
        ElAnalyzer analyzer = new ElAnalyzer(web, new FacesDocument(uri(host), text, 1)).withIndex(index.snapshot());
        List<Location> include = new DefinitionProvider(analyzer, u -> null).definition(text.indexOf("/shared-debug") + 3);
        assertEquals(1, include.size(), include.toString());
        assertEquals(uri(debug), include.get(0).getUri());

        List<String> paths = new CompletionProvider(analyzer, FacesSettings.defaults()).complete(text.indexOf("/shared-debug"))
                .stream().map(CompletionItem::getLabel).toList();
        assertTrue(paths.contains("/shared-debug/debug.xhtml"), paths.toString());

        // a relative include inside the other module
        ProjectModel shared = new ProjectModel(workspace.resolve("shared"), null);
        shared.refresh();
        String debugText = Files.readString(debug);
        ElAnalyzer inShared = new ElAnalyzer(shared, new FacesDocument(uri(debug), debugText, 1)).withIndex(index.snapshot());
        List<Location> relative = new DefinitionProvider(inShared, u -> null).definition(debugText.indexOf("panel.xhtml") + 2);
        assertEquals(uri(panel), relative.get(0).getUri());
    }

    @Test
    void navigatesFromDefinesToTheirSlotsThroughTheTemplateChain() throws Exception {
        Path base = write("web/src/main/webapp/WEB-INF/base.xhtml", "<html" + NS + "><h:body>\n"
                + "  <ui:insert name=\"header\"/><ui:insert name=\"title\"/><ui:insert name=\"content\"/>\n"
                + "</h:body></html>\n");
        Path inner = write("web/src/main/webapp/WEB-INF/inner.xhtml", "<ui:composition" + NS + " template=\"base.xhtml\">\n"
                + "  <ui:define name=\"title\">fixed</ui:define>\n"
                + "  <ui:define name=\"content\"><div><ui:insert name=\"content\"/><ui:include src=\"side.xhtml\"/></div></ui:define>\n"
                + "  <ui:insert name=\"content\"/>\n"
                + "</ui:composition>\n");
        Path side = write("web/src/main/webapp/WEB-INF/side.xhtml", "<ui:composition" + NS + ">\n"
                + "  <ui:insert name=\"side\"/>\n</ui:composition>\n");
        Path client = write("web/src/main/webapp/client.xhtml", "<ui:composition" + NS + " template=\"/WEB-INF/inner.xhtml\">\n"
                + "  <ui:define name=\"content\"/><ui:define name=\"side\"/><ui:define name=\"header\"/>"
                + "<ui:define name=\"title\"/><ui:define name=\"missing\"/>\n"
                + "</ui:composition>\n");
        index.rebuildNow();
        ProjectModel web = new ProjectModel(workspace.resolve("web"), null);
        web.refresh();
        String text = Files.readString(client);
        DefinitionProvider definitions = new DefinitionProvider(
                new ElAnalyzer(web, new FacesDocument(uri(client), text, 1)).withIndex(index.snapshot()), u -> null);
        java.util.function.Function<String, List<Location>> slotsOf =
                name -> definitions.definition(text.indexOf("\"" + name + "\"") + 2);

        // the insert inside inner's define, not the one outside it that Facelets drops
        List<Location> content = slotsOf.apply("content");
        assertEquals(1, content.size(), content.toString());
        assertEquals(uri(inner), content.get(0).getUri());
        String innerText = Files.readString(inner);
        int slotLine = (int) innerText.substring(0, innerText.indexOf("<div><ui:insert")).chars().filter(c -> c == '\n').count();
        assertEquals(slotLine, content.get(0).getRange().getStart().getLine());
        // a file the template includes
        assertEquals(List.of(uri(side)), slotsOf.apply("side").stream().map(Location::getUri).toList());
        // a name the intermediate template does not define reaches its own template
        assertEquals(List.of(uri(base)), slotsOf.apply("header").stream().map(Location::getUri).toList());
        // inner defines "title" itself, so the client's define fills nothing: falls back to the template
        List<Location> title = slotsOf.apply("title");
        assertEquals(List.of(uri(inner)), title.stream().map(Location::getUri).toList());
        assertEquals(0, title.get(0).getRange().getStart().getLine());
        assertEquals(0, slotsOf.apply("missing").get(0).getRange().getStart().getLine());
    }

    @Test
    void navigatesToAbsoluteClientIdsInOtherFiles() throws Exception {
        ElAnalyzer analyzer = analyzer(person);
        String text = analyzer.document().text();
        List<Location> locations = new DefinitionProvider(analyzer, u -> null).definition(text.indexOf(":f:p1:street") + 7);
        assertEquals(1, locations.size());
        assertEquals(uri(person), locations.get(0).getUri());

        // "f" alone resolves into index.xhtml, a different file
        List<Location> form = new DefinitionProvider(analyzer, u -> null).definition(text.indexOf(":f:p1:street") + 1);
        assertEquals(uri(page), form.get(0).getUri());
    }

    @Test
    void completesAbsoluteClientIds() throws Exception {
        ElAnalyzer analyzer = analyzer(angaben);
        String text = analyzer.document().text();
        int offset = text.indexOf("update=\":") + "update=\":".length();
        List<String> labels = new CompletionProvider(analyzer, FacesSettings.defaults()).complete(offset).stream()
                .map(CompletionItem::getLabel).toList();
        assertTrue(labels.containsAll(List.of(":header:title", ":f:p1:street", ":f:go")), labels.toString());
    }
}
