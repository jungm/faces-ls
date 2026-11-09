package io.github.jungm.fls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.features.CompletionProvider;
import io.github.jungm.fls.features.DefinitionProvider;
import io.github.jungm.fls.features.HoverProvider;
import io.github.jungm.fls.project.ProjectModel;

/** Faces and PrimeFaces search expressions in update/process/for attributes. */
class SearchExpressionTest {
    @TempDir
    Path project;

    private static final String PAGE = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:h="jakarta.faces.html" xmlns:p="primefaces">
            <h:body>
              <h:form id="form">
                <p:panel id="panel" widgetVar="panelWidget">
                  <div class="box">
                    <p:inputText id="name"/>
                    <p:commandButton id="save" process="@this @parent"
                        update="@form:@child(0) :form:name msgs @widgetVar(panelWidget) @namingcontainer @next @previous">
                      <p:ajax process="@this" update="@parent"/>
                    </p:commandButton>
                    <p:message id="msgs" for="name"/>
                  </div>
                </p:panel>
              </h:form>
            </h:body>
            </html>
            """;

    private ElAnalyzer analyzer() throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Path jar = project.resolve("primefaces.jar");
        StringBuilder taglib = new StringBuilder("<facelet-taglib><namespace>primefaces</namespace>");
        for (String component : List.of("panel", "inputText", "commandButton", "message")) {
            taglib.append("<tag><tag-name>").append(component).append("</tag-name><component><component-type>")
                    .append(component).append("</component-type></component><attribute><name>id</name></attribute></tag>");
        }
        taglib.append("<tag><tag-name>ajax</tag-name><attribute><name>update</name></attribute></tag></facelet-taglib>");
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("META-INF/primefaces.urn.taglib.xml"));
            zip.write(taglib.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path page = project.resolve("page.xhtml");
        Files.writeString(page, PAGE);
        ProjectModel model = new ProjectModel(project, null);
        model.refresh();
        model.useClasspath(List.of(jar));
        return new ElAnalyzer(model, new FacesDocument(page.toUri().toString(), PAGE, 1));
    }

    /** The ids (or tag names) go to definition reaches from the token at {@code needle} (+delta) in the given line. */
    private static List<String> targets(ElAnalyzer analyzer, String lineMarker, String needle, int delta) {
        int line = PAGE.indexOf(lineMarker);
        int offset = PAGE.indexOf(needle, line) + delta;
        List<Location> locations = new DefinitionProvider(analyzer, uri -> null).definition(offset);
        return locations.stream().map(location -> {
            int start = analyzer.document().lines().offset(location.getRange().getStart());
            int end = analyzer.document().lines().offset(location.getRange().getEnd());
            return PAGE.substring(start, end);
        }).toList();
    }

    @Test
    void resolvesKeywords() throws Exception {
        ElAnalyzer analyzer = analyzer();
        assertEquals(List.of("save"), targets(analyzer, "process=\"@this @parent\"", "@this", 2));
        // the div between button and panel is plain markup, not a component
        assertEquals(List.of("panel"), targets(analyzer, "process=\"@this @parent\"", "@parent", 2));
        assertEquals(List.of("form"), targets(analyzer, "update=\"@form", "@namingcontainer", 3));
        assertEquals(List.of("msgs"), targets(analyzer, "update=\"@form", "@next", 2));
        assertEquals(List.of("name"), targets(analyzer, "update=\"@form", "@previous", 2));
        assertEquals(List.of("panel"), targets(analyzer, "update=\"@form", "@widgetVar", 2));
    }

    @Test
    void resolvesChainsAndIds() throws Exception {
        ElAnalyzer analyzer = analyzer();
        // a chain resolves up to the segment under the cursor
        assertEquals(List.of("form"), targets(analyzer, "update=\"@form", "@form:", 2));
        assertEquals(List.of("panel"), targets(analyzer, "update=\"@form", "@child(0)", 2));
        assertEquals(List.of("name"), targets(analyzer, "update=\"@form", ":form:name", 7));
        assertEquals(List.of("msgs"), targets(analyzer, "update=\"@form", " msgs", 2));
        assertEquals(List.of("name"), targets(analyzer, "<p:message", "for=\"name", 6));
    }

    @Test
    void behaviorsResolveRelativeToTheirComponent() throws Exception {
        ElAnalyzer analyzer = analyzer();
        assertEquals(List.of("save"), targets(analyzer, "<p:ajax", "@this", 2));
        assertEquals(List.of("panel"), targets(analyzer, "<p:ajax", "@parent", 2));
    }

    @Test
    void hoverShowsTheResolvedComponent() throws Exception {
        ElAnalyzer analyzer = analyzer();
        int offset = PAGE.indexOf("@child(0)") + 3;
        Hover hover = new HoverProvider(analyzer).hover(offset);
        String text = hover.getContents().getRight().getValue();
        assertTrue(text.contains("`@form:@child(0)` → `<p:panel id=\"panel\">`"), text);
    }

    @Test
    void completesKeywordsAndWidgetVars() throws Exception {
        String text = PAGE.replace("update=\"@parent\"", "update=\"@form:@ch\"");
        ElAnalyzer analyzer = analyzer();
        Path page = project.resolve("page.xhtml");
        Files.writeString(page, text);
        ElAnalyzer edited = new ElAnalyzer(analyzer.project(), new FacesDocument(page.toUri().toString(), text, 2));
        int offset = text.indexOf("@form:@ch") + "@form:@ch".length();
        List<CompletionItem> items = new CompletionProvider(edited, FacesSettings.defaults()).complete(offset);
        CompletionItem child = items.stream().filter(i -> i.getLabel().equals("@child(0)")).findFirst().orElseThrow();
        assertEquals("@form:@child(0)", child.getFilterText());

        String widget = PAGE.replace("update=\"@parent\"", "update=\"@widgetVar(pa\"");
        Files.writeString(page, widget);
        ElAnalyzer withWidget = new ElAnalyzer(analyzer.project(), new FacesDocument(page.toUri().toString(), widget, 3));
        int at = widget.indexOf("@widgetVar(pa") + "@widgetVar(pa".length();
        assertTrue(new CompletionProvider(withWidget, FacesSettings.defaults()).complete(at).stream()
                .anyMatch(i -> i.getLabel().equals("panelWidget")));
    }
}
