package io.github.jungm.fls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.jungm.fls.index.FaceletsIndex;
import io.github.jungm.fls.index.ViewPaths;
import io.github.jungm.fls.project.LibraryJar;
import io.github.jungm.fls.project.ProjectModel;
import io.github.jungm.fls.project.WebRoots;
import io.github.jungm.fls.taglib.TagLibrary;

/** Web roots outside the Maven layout, view path resolution with and without the index, and the shared jar cache. */
class WebLayoutTest {
    @TempDir
    Path workspace;

    private static final String NS = " xmlns:ui=\"jakarta.faces.facelets\" xmlns:h=\"jakarta.faces.html\"";

    private Path write(String relative, String text) throws Exception {
        Path file = workspace.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    private static String uri(Path file) {
        return file.toUri().toString();
    }

    @Test
    void indexAndProjectAgreeOnWebRootsOutsideTheMavenLayout() throws Exception {
        write("app/WebContent/WEB-INF/web.xml", """
                <web-app><context-param><param-name>jakarta.faces.SEPARATOR_CHAR</param-name>
                <param-value>_</param-value></context-param></web-app>""");
        Path layout = write("app/WebContent/WEB-INF/layout.xhtml", "<html" + NS + "><ui:insert name=\"content\"/></html>");
        Path page = write("app/WebContent/index.xhtml", "<ui:composition" + NS + " template=\"/WEB-INF/layout.xhtml\">"
                + "<ui:define name=\"content\"><h:form id=\"f\"><ui:include src=\"parts/panel.xhtml\"/></h:form></ui:define>"
                + "</ui:composition>");
        Path panel = write("app/WebContent/parts/panel.xhtml", "<ui:composition" + NS + "><h:inputText id=\"name\"/></ui:composition>");
        Path component = write("app/WebContent/resources/comp/box.xhtml", "<ui:component" + NS + "/>");

        FaceletsIndex index = new FaceletsIndex(List.of(workspace), () -> { });
        index.rebuildNow();
        FaceletsIndex.Snapshot snapshot = index.snapshot();
        ProjectModel project = new ProjectModel(workspace.resolve("app"), null);
        project.refresh();

        Path webContent = workspace.resolve("app/WebContent");
        assertEquals(List.of(webContent), project.webRoots());
        for (Path file : List.of(layout, page, panel, component)) {
            FaceletsIndex.Entry entry = snapshot.entry(uri(file));
            assertEquals(project.webRootFor(file), entry.webRoot(), file.toString());
            assertEquals(WebRoots.locate(file), new WebRoots.Location(entry.webRoot(), entry.webPath(), entry.compositeKey()));
        }
        assertEquals("/parts/panel.xhtml", snapshot.entry(uri(panel)).webPath());
        assertEquals("comp/box.xhtml", snapshot.entry(uri(component)).compositeKey());
        assertEquals(List.of(uri(page)), snapshot.pages().stream().map(FaceletsIndex.Entry::uri).toList());
        assertEquals(List.of(uri(layout)), snapshot.uses(uri(page)).stream()
                .filter(e -> e.kind() == FaceletsIndex.Kind.TEMPLATE).map(FaceletsIndex.Edge::to).toList());
        assertEquals(Set.of(uri(page)), snapshot.pagesContaining(uri(panel)));
        assertEquals('_', snapshot.separatorFor(snapshot.entry(uri(page))));
    }

    @Test
    void resolvesViewPathsWithAndWithoutTheIndex() throws Exception {
        write("web/pom.xml", "<project/>");
        write("shared/pom.xml", "<project/>");
        Path page = write("web/src/main/webapp/pages/index.xhtml", "<ui:composition" + NS + "/>");
        Path header = write("web/src/main/webapp/WEB-INF/header.xhtml", "<ui:composition" + NS + "/>");
        Path sibling = write("web/src/main/webapp/pages/sibling.xhtml", "<ui:composition" + NS + "/>");
        Path debug = write("shared/src/main/resources/META-INF/resources/debug/panel.xhtml", "<ui:composition" + NS + "/>");
        ProjectModel project = new ProjectModel(workspace.resolve("web"), null);
        project.refresh();
        FaceletsIndex index = new FaceletsIndex(List.of(workspace), () -> { });
        index.rebuildNow();

        for (FaceletsIndex.Snapshot snapshot : new FaceletsIndex.Snapshot[] {null, index.snapshot()}) {
            assertEquals(Optional.of(header), ViewPaths.find(project, snapshot, uri(page), "/WEB-INF/header.xhtml"));
            assertEquals(Optional.of(header), ViewPaths.find(project, snapshot, uri(page), "../WEB-INF/header.xhtml"));
            assertEquals(Optional.of(sibling), ViewPaths.find(project, snapshot, uri(page), "sibling.xhtml"));
            assertEquals(Optional.empty(), ViewPaths.find(project, snapshot, uri(page), "../../outside.xhtml"));
            assertEquals(Optional.empty(), ViewPaths.find(project, snapshot, uri(page), "/missing.xhtml"));
            List<String> paths = ViewPaths.includable(project, snapshot, uri(page));
            assertTrue(paths.containsAll(List.of("/pages/index.xhtml", "/pages/sibling.xhtml", "/WEB-INF/header.xhtml")),
                    paths.toString());
        }
        // another module's META-INF/resources: through the index, or the classpath without it
        assertEquals(Optional.of(debug), ViewPaths.find(project, index.snapshot(), uri(page), "/debug/panel.xhtml"));
        assertTrue(ViewPaths.includable(project, index.snapshot(), uri(page)).contains("/debug/panel.xhtml"));
        project.useClasspath(List.of(workspace.resolve("shared/src/main/resources")));
        assertEquals(Optional.of(debug), ViewPaths.find(project, null, uri(page), "/debug/panel.xhtml"));
        assertTrue(ViewPaths.includable(project, null, uri(page)).contains("/debug/panel.xhtml"));
    }

    @Test
    void listsViewPathsWithoutTheIndexOncePerConfigurationLoad() throws Exception {
        write("web/pom.xml", "<project/>");
        Path page = write("web/src/main/webapp/index.xhtml", "<ui:composition" + NS + "/>");
        ProjectModel project = new ProjectModel(workspace.resolve("web"), null);
        project.refresh();
        List<String> first = ViewPaths.includable(project, null, uri(page));
        assertEquals(List.of("/index.xhtml"), first);
        write("web/src/main/webapp/later.xhtml", "<ui:composition" + NS + "/>");
        assertSame(first, ViewPaths.includable(project, null, uri(page)));
        project.reloadConfiguration();
        assertTrue(ViewPaths.includable(project, null, uri(page)).contains("/later.xhtml"));
    }

    private Path jar(String name, Map<String, String> entries) throws Exception {
        Path jar = workspace.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return jar;
    }

    private static Map<String, String> taglibJar(String functionName) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("META-INF/lib.taglib.xml", """
                <facelet-taglib><namespace>http://example.org/lib</namespace>
                  <function><function-name>%s</function-name><function-class>org.example.F</function-class>
                    <function-signature>java.lang.String %s(java.lang.String)</function-signature></function>
                </facelet-taglib>""".formatted(functionName, functionName));
        entries.put("META-INF/resources/lib/box.xhtml", "<ui:component" + NS + "/>");
        return entries;
    }

    @Test
    void readsEachJarVersionOnceForAllProjects() throws Exception {
        Path jar = jar("lib.jar", taglibJar("upper"));
        ProjectModel first = new ProjectModel(Files.createDirectories(workspace.resolve("one")), null);
        ProjectModel second = new ProjectModel(Files.createDirectories(workspace.resolve("two")), null);
        first.useClasspath(List.of(jar));
        second.useClasspath(List.of(jar));

        TagLibrary inFirst = first.taglibs().library("http://example.org/lib");
        TagLibrary inSecond = second.taglibs().library("http://example.org/lib");
        assertSame(inFirst, inSecond);
        assertSame(LibraryJar.of(jar), LibraryJar.of(jar));
        assertEquals(List.of("lib"), first.taglibs().compositeLibraryNames());
        assertSame(first.taglibs().library("jakarta.faces.composite/lib").tags().get("box"),
                second.taglibs().library("jakarta.faces.composite/lib").tags().get("box"));

        // a new version of the jar replaces the cached one
        LibraryJar old = LibraryJar.of(jar);
        jar("lib.jar", taglibJar("lowercase"));
        Files.setLastModifiedTime(jar, FileTime.fromMillis(Files.getLastModifiedTime(jar).toMillis() + 5000));
        LibraryJar current = LibraryJar.of(jar);
        assertNotSame(old, current);
        assertTrue(current.taglibs().get(0).functions().containsKey("lowercase"));
        assertFalse(current.taglibs().get(0).functions().containsKey("upper"));
        assertSame(current, LibraryJar.of(jar));
    }
}
