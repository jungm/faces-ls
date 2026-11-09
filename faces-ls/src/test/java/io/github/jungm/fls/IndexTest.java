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
