package io.github.jungm.fls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.features.CompletionProvider;
import io.github.jungm.fls.features.DefinitionProvider;
import io.github.jungm.fls.features.DiagnosticsProvider;
import io.github.jungm.fls.project.ProjectModel;

/** Composite components, taglibs, faces-config and bundles shipped in a library jar. */
class LibraryJarTest {
    @TempDir
    Path project;

    private static final String PAGE = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:h="jakarta.faces.html"
                  xmlns:s="http://example.org/shared" xmlns:sc="jakarta.faces.composite/shared">
              <h:outputText value="#{labels['order.title']} #{s:upper('x')}"/>
              <s:price amount="1"/>
              <sc:price />
              <s:
            </html>
            """;

    private Path jar() throws Exception {
        Path jar = project.resolve("shared.jar");
        Map<String, String> entries = Map.of(
                "META-INF/faces-config.xml", """
                        <faces-config><application><resource-bundle>
                          <base-name>org.example.labels</base-name><var>labels</var>
                        </resource-bundle></application></faces-config>""",
                "org/example/labels.properties", "order.title=Your order\n",
                "META-INF/shared.taglib.xml", """
                        <facelet-taglib><namespace>http://example.org/shared</namespace>
                          <composite-library-name>shared</composite-library-name>
                          <function><function-name>upper</function-name><function-class>org.example.Strings</function-class>
                            <function-signature>java.lang.String upper(java.lang.String)</function-signature></function>
                        </facelet-taglib>""",
                "META-INF/resources/shared/price.xhtml", """
                        <ui:component xmlns:ui="jakarta.faces.facelets" xmlns:cc="jakarta.faces.composite">
                          <cc:interface><cc:attribute name="amount" required="true"/></cc:interface>
                          <cc:implementation>#{cc.attrs.amount}</cc:implementation>
                        </ui:component>""");
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return jar;
    }

    private ElAnalyzer analyzer(String text) throws Exception {
        Files.createDirectories(project.resolve("src/main/webapp/WEB-INF"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Path page = project.resolve("src/main/webapp/order.xhtml");
        Files.writeString(page, text);
        ProjectModel model = new ProjectModel(project, null);
        model.refresh();
        model.useClasspath(List.of(jar()));
        return new ElAnalyzer(model, new FacesDocument(page.toUri().toString(), text, 1));
    }

    @Test
    void resolvesEverythingFromTheJar() throws Exception {
        List<String> messages = new DiagnosticsProvider(analyzer(PAGE)).diagnostics().stream().map(Diagnostic::getMessage).toList();
        // only the missing required attribute of <sc:price/> and the unterminated <s:
        assertTrue(messages.stream().noneMatch(m -> m.contains("Unknown") || m.contains("not found") || m.contains("resolve")),
                messages.toString());
        assertTrue(messages.stream().anyMatch(m -> m.contains("Missing required attribute 'amount'")), messages.toString());
    }

    @Test
    void completesCompositesOfTheTaglibNamespace() throws Exception {
        ElAnalyzer analyzer = analyzer(PAGE);
        int offset = PAGE.indexOf("<s:\n") + 3;
        List<CompletionItem> items = new CompletionProvider(analyzer, io.github.jungm.fls.FacesSettings.defaults()).complete(offset);
        assertTrue(items.stream().anyMatch(i -> i.getLabel().equals("s:price")),
                items.stream().map(CompletionItem::getLabel).toList().toString());
    }

    @Test
    void navigatesIntoExtractedJarEntries() throws Exception {
        ElAnalyzer analyzer = analyzer(PAGE);
        DefinitionProvider definitions = new DefinitionProvider(analyzer, uri -> null);
        List<Location> tag = definitions.definition(PAGE.indexOf("s:price") + 2);
        assertEquals(1, tag.size());
        assertTrue(tag.get(0).getUri().endsWith("META-INF/resources/shared/price.xhtml"), tag.toString());
        assertTrue(Files.exists(Path.of(java.net.URI.create(tag.get(0).getUri()))));
        List<Location> key = definitions.definition(PAGE.indexOf("order.title") + 2);
        assertEquals(1, key.size());
        assertTrue(key.get(0).getUri().endsWith("org/example/labels.properties"), key.toString());
        List<Location> var = definitions.definition(PAGE.indexOf("labels[") + 2);
        assertNotNull(var);
        assertTrue(var.get(0).getUri().endsWith("META-INF/faces-config.xml"), var.toString());
    }
}
