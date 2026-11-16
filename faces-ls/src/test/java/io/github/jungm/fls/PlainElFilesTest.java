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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.features.CompletionProvider;
import io.github.jungm.fls.features.DiagnosticsProvider;
import io.github.jungm.fls.features.HoverProvider;
import io.github.jungm.fls.project.ProjectModel;

/** EL in files that are no Facelets pages, like stylesheets and properties files. */
class PlainElFilesTest {
    @TempDir
    Path project;

    private ElAnalyzer analyzer(String relative, String text) throws Exception {
        Files.createDirectories(project.resolve("src/main/webapp/WEB-INF"));
        Files.createDirectories(project.resolve("src/main/resources/demo"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("src/main/webapp/WEB-INF/faces-config.xml"), """
                <faces-config><application><resource-bundle>
                  <base-name>demo.messages</base-name><var>msg</var>
                </resource-bundle></application></faces-config>""");
        Files.writeString(project.resolve("src/main/resources/demo/messages.properties"), "welcome=Hello\nbye=Bye\n");
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        ProjectModel model = new ProjectModel(project, null);
        model.refresh();
        return new ElAnalyzer(model, new FacesDocument(file.toUri().toString(), text, 1, false));
    }

    private static List<String> messages(ElAnalyzer analyzer) {
        return new DiagnosticsProvider(analyzer).diagnostics().stream().map(Diagnostic::getMessage).toList();
    }

    private static List<String> labels(List<CompletionItem> items) {
        return items.stream().map(CompletionItem::getLabel).toList();
    }

    @Test
    void stylesheetsHaveElButNoMarkup() throws Exception {
        String css = """
                nav > a { background: url("#{msg['wel']}"); }
                <h:bogus/>
                """;
        ElAnalyzer analyzer = analyzer("src/main/webapp/resources/css/site.css", css);
        assertFalse(analyzer.document().markup());
        assertEquals(1, analyzer.document().elSites().size());
        List<String> messages = messages(analyzer);
        assertEquals(1, messages.size(), messages.toString());
        assertTrue(messages.get(0).contains("Key 'wel' not found"), messages.toString());

        int inEl = css.indexOf("msg['wel") + "msg['wel".length();
        List<CompletionItem> items = new CompletionProvider(analyzer, FacesSettings.defaults()).complete(inEl);
        assertTrue(labels(items).contains("welcome"), labels(items).toString());
        int inTag = css.indexOf("<h:bo") + "<h:bo".length();
        assertEquals(List.of(), new CompletionProvider(analyzer, FacesSettings.defaults()).complete(inTag));
        assertNull(new HoverProvider(analyzer).hover(inTag));
        assertNotNull(new HoverProvider(analyzer).hover(css.indexOf("msg[") + 1));
    }

    @Test
    void propertiesCommentsHaveNoEl() throws Exception {
        String properties = """
                # #{msg['commented']}
                  ! #{msg['commented']}
                greeting=#{msg['welcome']}, #{msg['missing']}
                long=first \\
                  #{msg['continued']}
                #{msg['commentedToo']}
                """;
        ElAnalyzer analyzer = analyzer("src/main/resources/demo/labels.properties", properties);
        List<String> messages = messages(analyzer);
        assertEquals(2, messages.size(), messages.toString());
        assertTrue(messages.stream().anyMatch(m -> m.contains("'missing'")), messages.toString());
        assertTrue(messages.stream().anyMatch(m -> m.contains("'continued'")), messages.toString());
    }
}
