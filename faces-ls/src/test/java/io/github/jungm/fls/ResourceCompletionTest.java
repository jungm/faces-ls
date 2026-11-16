package io.github.jungm.fls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.features.CompletionProvider;
import io.github.jungm.fls.project.ProjectModel;

/** Completion of resource references: tag attributes, {@code #{resource[...]}}, contracts. */
class ResourceCompletionTest {
    @TempDir
    Path project;

    private static final String NS = " xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:h=\"jakarta.faces.html\""
            + " xmlns:f=\"jakarta.faces.core\" xmlns:ui=\"jakarta.faces.facelets\" xmlns:jsf=\"jakarta.faces\"";

    private Path write(String relative, String text) throws Exception {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    private ProjectModel model(List<Path> classpath) throws Exception {
        write("pom.xml", "<project/>");
        Files.createDirectories(project.resolve("src/main/webapp/WEB-INF"));
        ProjectModel model = new ProjectModel(project, null);
        model.refresh();
        if (!classpath.isEmpty()) {
            model.useClasspath(classpath);
        }
        return model;
    }

    /** Completion at the '|' in {@code body}, put into a page at {@code relative}. */
    private List<CompletionItem> complete(ProjectModel model, String relative, String body) throws Exception {
        String text = "<ui:composition" + NS + ">" + body.replace("|", "") + "</ui:composition>";
        int offset = ("<ui:composition" + NS + ">").length() + body.indexOf('|');
        Path page = write(relative, text);
        ElAnalyzer analyzer = new ElAnalyzer(model, new FacesDocument(page.toUri().toString(), text, 1));
        return new CompletionProvider(analyzer, FacesSettings.defaults()).complete(offset);
    }

    private List<String> labels(ProjectModel model, String body) throws Exception {
        return labels(model, "src/main/webapp/page.xhtml", body);
    }

    private List<String> labels(ProjectModel model, String relative, String body) throws Exception {
        return complete(model, relative, body).stream().map(CompletionItem::getLabel).filter(l -> !l.equals("#{…}")).toList();
    }

    private void project() throws Exception {
        write("src/main/webapp/resources/images/logo.png", "png");
        write("src/main/webapp/resources/images/icons/save.svg", "svg");
        write("src/main/webapp/resources/css/site.css", "css");
        write("src/main/webapp/resources/app.js", "js");
        write("src/main/webapp/resources/corp/1_0/old.png", "old");
        write("src/main/webapp/resources/corp/1_2/logo.png", "new");
        write("src/main/webapp/resources/corp/1_2/script.js/1_0.js", "old");
        write("src/main/webapp/resources/corp/1_2/script.js/1_1.js", "new");
        write("src/main/resources/META-INF/resources/theme/theme.css", "css");
    }

    @Test
    void completesLibrariesAndNamesOfResourceTags() throws Exception {
        project();
        ProjectModel model = model(List.of());

        assertEquals(List.of("corp", "css", "images", "theme"), labels(model, "<h:graphicImage library=\"|\"/>"));
        assertEquals(List.of("icons/save.svg", "logo.png"), labels(model, "<h:graphicImage library=\"images\" name=\"|\"/>"));
        // the highest library version, versioned resources by their name
        assertEquals(List.of("logo.png", "script.js"), labels(model, "<h:graphicImage name=\"|\" library=\"corp\"/>"));
        // outside a library: every path, but not through library versions
        List<String> plain = labels(model, "<h:graphicImage name=\"|\"/>");
        assertEquals(List.of("app.js", "css/site.css", "images/icons/save.svg", "images/logo.png", "theme/theme.css"), plain);
        // only stylesheets and scripts where those are expected
        assertEquals(List.of("css/site.css", "theme/theme.css"), labels(model, "<h:outputStylesheet name=\"|\"/>"));
        assertEquals(List.of("script.js"), labels(model, "<h:outputScript library=\"corp\" name=\"|\"/>"));
        // a library that is an expression is unknown
        assertEquals(List.of(), labels(model, "<h:graphicImage library=\"#{bean.library}\" name=\"|\"/>"));
        // passthrough elements
        assertEquals(List.of("icons/save.svg", "logo.png"), labels(model, "<img jsf:library=\"images\" jsf:name=\"|\"/>"));
    }

    @Test
    void replacesWhatIsTyped() throws Exception {
        project();
        ProjectModel model = model(List.of());
        CompletionItem item = complete(model, "src/main/webapp/page.xhtml", "<h:graphicImage library=\"images\" name=\"ic|\"/>")
                .stream().filter(i -> i.getLabel().equals("icons/save.svg")).findFirst().orElseThrow();
        assertEquals(2, item.getTextEdit().getLeft().getRange().getEnd().getCharacter()
                - item.getTextEdit().getLeft().getRange().getStart().getCharacter());
    }

    @Test
    void completesResourceExpressions() throws Exception {
        project();
        ProjectModel model = model(List.of());

        List<String> top = labels(model, "<h:graphicImage value=\"#{resource['|']}\"/>");
        assertTrue(top.containsAll(List.of("corp:", "css:", "images:", "theme:", "app.js", "images/logo.png")), top.toString());
        assertTrue(!top.contains("this:"), top.toString());
        CompletionItem library = complete(model, "src/main/webapp/page.xhtml", "#{resource['|']}").stream()
                .filter(i -> i.getLabel().equals("images:")).findFirst().orElseThrow();
        assertEquals("editor.action.triggerSuggest", library.getCommand().getCommand());

        assertEquals(List.of("icons/save.svg", "logo.png"), labels(model, "<h:graphicImage value=\"#{resource['images:|']}\"/>"));
        assertEquals(List.of("logo.png", "script.js"), labels(model, "<div>#{resource[\"corp:lo|\"]}</div>"));
        CompletionItem logo = complete(model, "src/main/webapp/page.xhtml", "#{resource['corp:lo|']}").stream()
                .filter(i -> i.getLabel().equals("logo.png")).findFirst().orElseThrow();
        assertEquals(2, logo.getTextEdit().getLeft().getRange().getEnd().getCharacter()
                - logo.getTextEdit().getLeft().getRange().getStart().getCharacter());
    }

    @Test
    void completesThisInCompositesAndContracts() throws Exception {
        write("src/main/webapp/resources/comp/box.css", "css");
        write("src/main/webapp/resources/comp/box.xhtml", "");
        write("src/main/webapp/contracts/site/site.css", "css");
        write("src/main/webapp/contracts/site/template.xhtml", "");
        ProjectModel model = model(List.of());

        assertTrue(labels(model, "src/main/webapp/resources/comp/box.xhtml", "#{resource['|']}").contains("this:"));
        assertEquals(List.of("box.css", "box.xhtml"),
                labels(model, "src/main/webapp/resources/comp/box.xhtml", "#{resource['this:|']}"));
        assertEquals(List.of("site.css", "template.xhtml"),
                labels(model, "src/main/webapp/contracts/site/template.xhtml", "#{resource['this:|']}"));
    }

    @Test
    void completesResourcesOfJarsAndContracts() throws Exception {
        Path jar = project.resolve("faces-impl.jar");
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : Map.of("META-INF/resources/jakarta.faces/faces.js", "faces",
                    "META-INF/resources/jakarta.faces/nested/x.js", "x",
                    "META-INF/contracts/dark/jakarta.faces.contract.xml", "").entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        write("src/main/webapp/contracts/site/css/main.css", "css");
        write("src/main/webapp/WEB-INF/res/de/flags/flag.png", "de");
        write("src/main/webapp/WEB-INF/web.xml", """
                <web-app><context-param><param-name>jakarta.faces.WEBAPP_RESOURCES_DIRECTORY</param-name>
                <param-value>WEB-INF/res</param-value></context-param></web-app>""");
        ProjectModel model = model(List.of(jar));

        List<String> libraries = labels(model, "<h:outputScript library=\"|\"/>");
        assertTrue(libraries.containsAll(List.of("jakarta.faces", "css", "de", "flags")), libraries.toString());
        assertEquals(List.of("faces.js", "nested/x.js"), labels(model, "<h:outputScript library=\"jakarta.faces\" name=\"|\"/>"));
        assertEquals(List.of("main.css"), labels(model, "<h:outputStylesheet library=\"css\" name=\"|\"/>"));
        assertEquals(List.of("flag.png"), labels(model, "<h:graphicImage library=\"flags\" name=\"|\"/>"));
        // jar resources only inside their libraries
        assertTrue(!labels(model, "<h:outputScript name=\"|\"/>").contains("jakarta.faces/faces.js"));

        assertEquals(List.of("dark", "site"), labels(model, "<f:view contracts=\"|\"/>"));
        assertEquals(List.of("site"), labels(model, "<f:view contracts=\"dark, |\"/>"));
        CompletionItem site = complete(model, "src/main/webapp/page.xhtml", "<f:view contracts=\"dark, s|\"/>").stream()
                .filter(i -> i.getLabel().equals("site")).findFirst().orElseThrow();
        assertEquals(1, site.getTextEdit().getLeft().getRange().getEnd().getCharacter()
                - site.getTextEdit().getLeft().getRange().getStart().getCharacter());
    }
}
