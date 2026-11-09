package io.github.jungm.fls;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.analysis.LineIndex;
import io.github.jungm.fls.features.JavaUsages;
import io.github.jungm.fls.index.FaceletsIndex;
import io.github.jungm.fls.project.ProjectModel;

/** EL uses of Java members across Facelets files: what the Java call hierarchy shows as Facelets callers. */
class JavaUsagesTest {
    @TempDir
    Path workspace;

    FakeJdt jdt;
    ProjectModel project;
    FaceletsIndex index;
    Path users;
    Path other;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        users = write("src/main/webapp/users.xhtml", """
                <html xmlns:h="jakarta.faces.html" xmlns:o="omnifaces" xmlns:s="http://example.org/shared">
                  <h:inputText value="#{userBean.name}"/>
                  <h:commandButton action="#{userBean.save}"/>
                  <h:outputText value="#{userBean.getName()} #{userBean.save()} #{other.name}"/>
                  <o:importFunctions type="org.example.Fmt"/>
                  <h:outputText value="#{Fmt:upper(userBean.name)} #{s:shout('x')}"/>
                </html>
                """);
        other = write("src/main/webapp/other.xhtml", """
                <html xmlns:h="jakarta.faces.html"><h:outputText value="#{other.name}"/></html>
                """);
        write("src/main/webapp/WEB-INF/shared.taglib.xml", """
                <facelet-taglib><namespace>http://example.org/shared</namespace>
                  <function><function-name>shout</function-name><function-class>org.example.Fmt</function-class>
                    <function-signature>java.lang.String upper(java.lang.String)</function-signature></function>
                </facelet-taglib>
                """);
        jdt = new FakeJdt(workspace, workspace.resolve("rendezvous"))
                .type("org.example.UserBean", List.of(), List.of(), List.of(
                        "java.lang.String getName()", "void setName(java.lang.String)", "java.lang.String save()"))
                .type("org.example.Other", List.of(), List.of(), List.of("java.lang.String getName()"))
                .type("org.example.Fmt", List.of(), List.of(), List.of("static java.lang.String upper(java.lang.String)"))
                .bean("userBean", "org.example.UserBean")
                .bean("other", "org.example.Other");
        project = new ProjectModel(workspace, jdt.client());
        project.refresh();
        FakeJdt.awaitBeans(project);
        index = new FaceletsIndex(List.of(workspace), () -> { });
        index.rebuildNow();
    }

    @AfterEach
    void tearDown() throws Exception {
        jdt.close();
    }

    private Path write(String relative, String text) throws Exception {
        Path file = workspace.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    /** The text at each usage, by file name. */
    private Map<String, List<String>> usages(String type, String name, int parameterCount) {
        FaceletsIndex.Snapshot snapshot = index.snapshot();
        Map<String, List<Range>> found = new JavaUsages(snapshot, entry -> new ElAnalyzer(project,
                new FacesDocument(entry.uri(), entry.text(), 0)).withIndex(snapshot))
                .find(new JavaUsages.Member(type, name, "method", parameterCount), project.taglibs().projectLibraries());
        Map<String, List<String>> result = new java.util.TreeMap<>();
        for (Map.Entry<String, List<Range>> file : found.entrySet()) {
            String text = snapshot.entry(file.getKey()).text();
            LineIndex lines = new LineIndex(text);
            List<String> names = new ArrayList<>();
            for (Range range : file.getValue()) {
                names.add(text.substring(lines.offset(range.getStart()), lines.offset(range.getEnd())));
            }
            result.put(Path.of(URI.create(file.getKey())).getFileName().toString(), names);
        }
        return result;
    }

    @Test
    void findsPropertiesThroughTheirAccessors() {
        // #{other.name} is a different class's property
        assertEquals(Map.of("users.xhtml", List.of("name", "getName", "name")), usages("org.example.UserBean", "getName", 0));
        assertEquals(Map.of("users.xhtml", List.of("name", "name")), usages("org.example.UserBean", "setName", 1));
        assertEquals(Map.of("users.xhtml", List.of("name"), "other.xhtml", List.of("name")),
                usages("org.example.Other", "getName", 0));
    }

    @Test
    void findsMethodExpressionsAndCalls() {
        assertEquals(Map.of("users.xhtml", List.of("save", "save")), usages("org.example.UserBean", "save", 0));
    }

    @Test
    void findsImportedAndTaglibFunctions() {
        assertEquals(Map.of("users.xhtml", List.of("Fmt:upper", "s:shout")), usages("org.example.Fmt", "upper", 1));
    }
}
