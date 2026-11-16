package io.github.jungm.fls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.eclipse.lsp4j.Hover;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.features.HoverProvider;
import io.github.jungm.fls.project.ProjectModel;

/** Hover on resource references previews the files they resolve to. */
class ResourceHoverTest {
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

    private Path image(String relative, int width, int height) throws Exception {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
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

    private Hover hover(ProjectModel model, String body, String marker, int delta) throws Exception {
        String text = "<ui:composition" + NS + ">" + body + "</ui:composition>";
        Path page = write("src/main/webapp/page.xhtml", text);
        int offset = text.indexOf(marker);
        assertTrue(offset >= 0, marker);
        ElAnalyzer analyzer = new ElAnalyzer(model, new FacesDocument(page.toUri().toString(), text, 1));
        return new HoverProvider(analyzer).hover(offset + delta);
    }

    private String text(ProjectModel model, String body, String marker, int delta) throws Exception {
        Hover hover = hover(model, body, marker, delta);
        return hover == null ? null : hover.getContents().getRight().getValue();
    }

    @Test
    void rendersImagesScaledDownToTheHover() throws Exception {
        Path logo = image("src/main/webapp/resources/images/logo.png", 640, 320);
        image("src/main/webapp/resources/images/icon.png", 16, 16);
        ProjectModel model = model(List.of());

        String text = text(model, "<h:graphicImage library=\"images\" name=\"logo.png\"/>", "logo.png", 1);
        assertTrue(text.startsWith("**logo.png** · 640 × 320 px · "), text);
        assertTrue(text.contains("`src/main/webapp/resources/images/logo.png`"), text);
        assertTrue(text.contains("![logo.png](" + logo.toUri() + "|width=320,height=160)"), text);

        String icon = text(model, "<h:graphicImage library=\"images\" name=\"icon.png\"/>", "\"images\"", 2);
        assertTrue(icon.contains("16 × 16 px"), icon);
        assertTrue(icon.endsWith(".png)"), icon);

        // the range is the attribute value
        Hover hover = hover(model, "<h:graphicImage library=\"images\" name=\"logo.png\"/>", "logo.png", 3);
        String page = Files.readString(project.resolve("src/main/webapp/page.xhtml"));
        int column = page.indexOf("logo.png");
        assertEquals(column, hover.getRange().getStart().getCharacter());
        assertEquals(column + "logo.png".length(), hover.getRange().getEnd().getCharacter());
    }

    @Test
    void readsTheSizeOfSvgsAndImageUrls() throws Exception {
        write("src/main/webapp/resources/icons/save.svg", "<?xml version=\"1.0\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\""
                + " viewBox=\"0 0 24 24\"><path d=\"M0 0\"/></svg>");
        write("src/main/webapp/resources/icons/big.svg", "<svg width=\"1000px\" height='500' viewBox=\"0 0 10 10\"/>");
        image("src/main/webapp/img/plain.png", 30, 20);
        ProjectModel model = model(List.of());

        assertTrue(text(model, "<h:graphicImage name=\"icons/save.svg\"/>", "save", 1).contains("24 × 24 px"));
        String big = text(model, "<h:graphicImage name=\"icons/big.svg\"/>", "big", 1);
        assertTrue(big.contains("1000 × 500 px") && big.endsWith("|width=320,height=160)"), big);
        assertTrue(text(model, "<h:graphicImage value=\"/img/plain.png\"/>", "plain", 1).contains("30 × 20 px"));
        assertTrue(text(model, "<h:graphicImage url=\"img/plain.png\"/>", "plain", 1).contains("30 × 20 px"));
    }

    @Test
    void showsTheStartOfTextFiles() throws Exception {
        String css = IntStream.rangeClosed(1, 30).mapToObj(i -> ".c" + i + " { color: red; }").collect(Collectors.joining("\n"));
        write("src/main/webapp/resources/css/site.css", css);
        write("src/main/webapp/resources/app.js", "const a = ```;\n");
        ProjectModel model = model(List.of());

        String text = text(model, "<h:outputStylesheet library=\"css\" name=\"site.css\"/>", "site.css", 1);
        assertTrue(text.startsWith("**site.css** · 30 lines · "), text);
        assertTrue(text.contains("```css\n.c1 { color: red; }\n"), text);
        assertTrue(text.contains(".c25 {") && !text.contains(".c26 {"), text);
        assertTrue(text.endsWith("… 5 more lines"), text);

        String script = text(model, "<h:outputScript name=\"app.js\"/>", "app.js", 1);
        assertTrue(script.contains("````javascript\nconst a = ```;\n````"), script);
    }

    @Test
    void showsBinaryFilesAndJarEntriesAndOtherMatches() throws Exception {
        Path jar = project.resolve("faces-impl.jar");
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("META-INF/resources/jakarta.faces/faces.js"));
            zip.write("var faces = {};".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.createDirectories(project.resolve("src/main/webapp/resources/fonts"));
        Files.write(project.resolve("src/main/webapp/resources/fonts/a.woff"), new byte[] {'w', 0, 'f', 0});
        write("src/main/webapp/resources/css/main.css", "a {}");
        write("src/main/webapp/contracts/site/css/main.css", "b {}");
        ProjectModel model = model(List.of(jar));

        String faces = text(model, "<h:outputScript library=\"jakarta.faces\" name=\"faces.js\"/>", "faces.js", 1);
        assertTrue(faces.contains("`faces-impl.jar!/META-INF/resources/jakarta.faces/faces.js`"), faces);
        assertTrue(faces.contains("var faces = {};"), faces);

        String font = text(model, "<h:outputStylesheet library=\"fonts\" name=\"a.woff\"/>", "a.woff", 1);
        assertEquals("**a.woff** · 4 B\n\n`src/main/webapp/resources/fonts/a.woff`", font);

        String css = text(model, "<h:outputStylesheet library=\"css\" name=\"main.css\"/>", "main.css", 1);
        assertTrue(css.contains("a {}"), css);
        assertTrue(css.endsWith("Also: `src/main/webapp/contracts/site/css/main.css`"), css);
    }

    @Test
    void previewsResourceExpressionsAndContracts() throws Exception {
        write("src/main/webapp/resources/css/top.css", "body {}");
        write("src/main/webapp/contracts/site/template.xhtml", "<html/>");
        Files.createDirectories(project.resolve("src/main/webapp/contracts/bare/css"));
        ProjectModel model = model(List.of());

        String key = text(model, "<link href=\"#{resource['css:top.css']}\"/>", "css:top", 2);
        assertTrue(key.startsWith("**top.css**") && key.contains("body {}"), key);

        String body = "<f:view contracts=\"site, bare\"/>";
        assertTrue(text(model, body, "site", 1).startsWith("**template.xhtml**"));
        String bare = text(model, body, "bare", 1);
        assertEquals("**bare** — directory\n\n`src/main/webapp/contracts/bare`\n\n`css/`", bare);
        Hover hover = hover(model, body, "bare", 1);
        assertEquals(4, hover.getRange().getEnd().getCharacter() - hover.getRange().getStart().getCharacter());
    }

    @Test
    void staysQuietForUnknownResources() throws Exception {
        ProjectModel model = model(List.of());
        assertNull(hover(model, "<h:graphicImage library=\"images\" name=\"none.png\"/>", "none", 1));
        assertNull(hover(model, "<h:graphicImage value=\"http://example.com/a.png\"/>", "example", 1));
        String text = text(model, "<h:graphicImage library=\"images\" name=\"none.png\"/>", "graphicImage", 1);
        assertFalse(text == null || text.isEmpty());
    }
}
