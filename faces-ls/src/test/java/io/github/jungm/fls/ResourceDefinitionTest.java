package io.github.jungm.fls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.features.DefinitionProvider;
import io.github.jungm.fls.features.DiagnosticsProvider;
import io.github.jungm.fls.project.ProjectModel;
import io.github.jungm.fls.project.Resources;

/** Go to definition on resource references: tag attributes, {@code #{resource[...]}}, contracts. */
class ResourceDefinitionTest {
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

    /** The files the definition at the first occurrence of {@code marker} (plus {@code delta}) points to. */
    private static List<Path> definitions(ProjectModel model, Path page, String text, String marker, int delta) {
        int offset = text.indexOf(marker);
        assertTrue(offset >= 0, marker);
        ElAnalyzer analyzer = new ElAnalyzer(model, new FacesDocument(page.toUri().toString(), text, 1));
        return new DefinitionProvider(analyzer, uri -> null).definition(offset + delta).stream()
                .map(Location::getUri).map(uri -> Path.of(URI.create(uri))).toList();
    }

    private Path page(String body) throws Exception {
        return write("src/main/webapp/page.xhtml", "<ui:composition" + NS + ">" + body + "</ui:composition>");
    }

    @Test
    void jumpsFromNameAndLibraryOfResourceTags() throws Exception {
        Path logo = write("src/main/webapp/resources/images/logo.png", "png");
        Path script = write("src/main/webapp/resources/app.js", "js");
        Path style = write("src/main/resources/META-INF/resources/theme/site.css", "css");
        ProjectModel model = model(List.of());
        String text = "<h:graphicImage library=\"images\" name=\"logo.png\"/><h:outputScript name=\"app.js\"/>"
                + "<h:outputStylesheet library=\"theme\" name=\"site.css\"/><h:graphicImage library=\"images\" name=\"none.png\"/>";
        Path page = page(text);
        text = Files.readString(page);

        assertEquals(List.of(logo), definitions(model, page, text, "logo.png", 2));
        assertEquals(List.of(logo), definitions(model, page, text, "\"images\"", 2));
        assertEquals(List.of(script), definitions(model, page, text, "app.js", 1));
        assertEquals(List.of(style), definitions(model, page, text, "site.css", 1));
        assertEquals(List.of(), definitions(model, page, text, "none.png", 1));
    }

    @Test
    void jumpsFromResourceExpressions() throws Exception {
        Path logo = write("src/main/webapp/resources/images/logo.png", "png");
        Path top = write("src/main/webapp/resources/top.css", "css");
        ProjectModel model = model(List.of());
        String text = "<h:graphicImage value=\"#{resource['images:logo.png']}\"/>"
                + "<div style=\"background: url(#{resource['top.css']})\">#{resource[\"images:logo.png\"]}</div>";
        Path page = page(text);
        text = Files.readString(page);

        assertEquals(List.of(logo), definitions(model, page, text, "images:logo.png", 3));
        assertEquals(List.of(top), definitions(model, page, text, "'top.css'", 3));
        assertEquals(List.of(logo), definitions(model, page, text, "\"images:logo.png\"", 3));
    }

    @Test
    void picksTheHighestLibraryAndResourceVersion() throws Exception {
        write("src/main/webapp/resources/corp/1_0/logo.png", "old");
        Path newest = write("src/main/webapp/resources/corp/1_10/logo.png", "new");
        write("src/main/webapp/resources/corp/1_9/logo.png", "older");
        write("src/main/webapp/resources/basic/script.js/1_3_4.js", "old");
        Path script = write("src/main/webapp/resources/basic/script.js/1_12.js", "new");
        ProjectModel model = model(List.of());
        String text = "<h:graphicImage library=\"corp\" name=\"logo.png\"/><h:outputScript library=\"basic\" name=\"script.js\"/>";
        Path page = page(text);
        text = Files.readString(page);

        assertEquals(List.of(newest), definitions(model, page, text, "logo.png", 1));
        assertEquals(List.of(script), definitions(model, page, text, "script.js", 1));
    }

    @Test
    void findsResourcesInJarsLocalizedAndInAConfiguredDirectory() throws Exception {
        Path jar = project.resolve("faces-impl.jar");
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : Map.of("META-INF/resources/jakarta.faces/faces.js", "faces",
                    "META-INF/contracts/dark/jakarta.faces.contract.xml", "").entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        write("src/main/webapp/WEB-INF/web.xml", """
                <web-app><context-param><param-name>jakarta.faces.WEBAPP_RESOURCES_DIRECTORY</param-name>
                <param-value>WEB-INF/res</param-value></context-param></web-app>""");
        Path flag = write("src/main/webapp/WEB-INF/res/de/flags/flag.png", "de");
        write("src/main/webapp/resources/flags/flag.png", "not the resources directory");
        ProjectModel model = model(List.of(jar));
        String text = "<h:outputScript library=\"jakarta.faces\" name=\"faces.js\"/><h:graphicImage library=\"flags\" name=\"flag.png\"/>"
                + "<f:view contracts=\"dark\"/>";
        Path page = page(text);
        text = Files.readString(page);

        List<Path> faces = definitions(model, page, text, "faces.js", 1);
        assertEquals(1, faces.size());
        assertEquals("faces", Files.readString(faces.get(0)));
        assertTrue(faces.get(0).endsWith(Path.of("META-INF/resources/jakarta.faces/faces.js")), faces.toString());
        assertEquals(List.of(flag), definitions(model, page, text, "flag.png", 1));
        List<Path> contract = definitions(model, page, text, "\"dark\"", 2);
        assertEquals(1, contract.size());
        assertTrue(contract.get(0).endsWith(Path.of("META-INF/contracts/dark/jakarta.faces.contract.xml")), contract.toString());
    }

    @Test
    void jumpsFromPassthroughElementsAndImageUrls() throws Exception {
        Path logo = write("src/main/webapp/resources/images/logo.png", "png");
        Path plain = write("src/main/webapp/img/plain.png", "png");
        ProjectModel model = model(List.of());
        String text = "<img jsf:library=\"images\" jsf:name=\"logo.png\" alt=\"logo\"/><h:graphicImage value=\"/img/plain.png\"/>"
                + "<h:graphicImage url=\"img/plain.png\"/>";
        Path page = page(text);
        text = Files.readString(page);

        assertEquals(List.of(logo), definitions(model, page, text, "logo.png", 1));
        assertEquals(List.of(logo), definitions(model, page, text, "\"images\"", 2));
        assertEquals(List.of(plain), definitions(model, page, text, "/img/plain.png", 2));
        assertEquals(List.of(plain), definitions(model, page, text, "\"img/plain.png", 2));
    }

    @Test
    void resolvesThisToTheLibraryOrContractOfTheFile() throws Exception {
        Path boxCss = write("src/main/webapp/resources/comp/box.css", "css");
        Path siteCss = write("src/main/webapp/contracts/site/site.css", "css");
        Path contractStyle = write("src/main/webapp/contracts/site/css/main.css", "css");
        Path marker = write("src/main/webapp/contracts/other/jakarta.faces.contract.xml", "");
        ProjectModel model = model(List.of());
        Path box = write("src/main/webapp/resources/comp/box.xhtml",
                "<ui:component" + NS + "><h:outputStylesheet name=\"#{resource['this:box.css']}\"/></ui:component>");
        String boxText = Files.readString(box);
        assertEquals(List.of(boxCss), definitions(model, box, boxText, "this:box.css", 2));

        Path template = write("src/main/webapp/contracts/site/template.xhtml",
                "<html" + NS + "><link href=\"#{resource['this:site.css']}\"/></html>");
        String templateText = Files.readString(template);
        assertEquals(List.of(siteCss), definitions(model, template, templateText, "this:site.css", 2));

        // a resource of a library inside a contract, and the contracts of a view
        String text = "<f:view contracts=\"site, other\"><h:outputStylesheet library=\"css\" name=\"main.css\"/></f:view>";
        Path page = page(text);
        text = Files.readString(page);
        assertEquals(List.of(contractStyle), definitions(model, page, text, "main.css", 1));
        assertEquals(List.of(marker), definitions(model, page, text, "other\"", 1));
        assertEquals(List.of(template), definitions(model, page, text, "site,", 1));
    }

    @Test
    void resourceKeysAreNotJavaPropertiesWithTheJavaModel() throws Exception {
        Path logo = write("src/main/resources/META-INF/resources/brand/logo.png", "png");
        write("pom.xml", "<project/>");
        String text = "<img src=\"#{resource['brand:logo.png']}\" alt=\"\"/><h:outputText value=\"#{user.nope}\"/>";
        Path page = page(text);
        text = Files.readString(page);
        try (FakeJdt jdt = new FakeJdt(project, project.resolve("rendezvous"))
                .type("jakarta.faces.application.ResourceHandler", List.of(), List.of(),
                        List.of("boolean isResourceRequest(jakarta.faces.context.FacesContext)"))
                .type("org.example.User", List.of(), List.of(), List.of("java.lang.String getName()"))
                .bean("user", "org.example.User")) {
            ProjectModel model = new ProjectModel(project, jdt.client());
            model.refresh();
            FakeJdt.awaitBeans(model);
            ElAnalyzer analyzer = new ElAnalyzer(model, new FacesDocument(page.toUri().toString(), text, 1));
            List<String> problems = new DiagnosticsProvider(analyzer).diagnostics().stream()
                    .map(org.eclipse.lsp4j.Diagnostic::getMessage).toList();
            assertEquals(List.of("Cannot resolve property or method 'nope' in User"), problems.stream()
                    .filter(m -> m.startsWith("Cannot resolve")).toList(), problems.toString());
            assertEquals(List.of(logo), definitions(model, page, text, "logo.png", 1));
        }
    }

    @Test
    void parsesResourceKeys() {
        assertEquals(new Resources.Reference(null, "a.css"), Resources.Reference.parse("a.css"));
        assertEquals(new Resources.Reference("lib", "img/a.png"), Resources.Reference.parse("lib:img/a.png"));
        assertEquals(null, Resources.Reference.parse("a:b:c"));
        assertEquals(null, Resources.Reference.parse(":a"));
        assertEquals(List.of(), Resources.find(new ProjectModel(project, null), "lib", "../../etc/passwd"));
    }
}
