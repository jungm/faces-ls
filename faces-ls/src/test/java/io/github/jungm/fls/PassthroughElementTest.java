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
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.features.CompletionProvider;
import io.github.jungm.fls.features.DefinitionProvider;
import io.github.jungm.fls.features.DiagnosticsProvider;
import io.github.jungm.fls.features.HoverProvider;
import io.github.jungm.fls.project.ProjectModel;

/** Plain HTML elements turned into components by a {@code jsf:} attribute. */
class PassthroughElementTest {
    @TempDir
    Path project;

    private ElAnalyzer analyzer(String body) throws Exception {
        return analyzer(body, "xmlns:jsf=\"jakarta.faces\"");
    }

    private ElAnalyzer analyzer(String body, String declarations) throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        String text = "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:h=\"jakarta.faces.html\" " + declarations + ">\n"
                + body + "\n</html>\n";
        Path page = project.resolve("page.xhtml");
        Files.writeString(page, text);
        ProjectModel model = new ProjectModel(project, null);
        model.refresh();
        return new ElAnalyzer(model, new FacesDocument(page.toUri().toString(), text, 1));
    }

    private static int at(ElAnalyzer analyzer, String marker) {
        int index = analyzer.document().text().indexOf(marker);
        assertTrue(index >= 0, marker);
        return index;
    }

    private static String hoverText(ElAnalyzer analyzer, String marker) {
        Hover hover = new HoverProvider(analyzer).hover(at(analyzer, marker) + 1);
        return hover == null ? null : hover.getContents().getRight().getValue();
    }

    private static List<String> completions(ElAnalyzer analyzer, int offset) {
        return new CompletionProvider(analyzer, FacesSettings.defaults()).complete(offset).stream()
                .map(CompletionItem::getLabel).toList();
    }

    private static List<String> warnings(ElAnalyzer analyzer) {
        return new DiagnosticsProvider(analyzer).diagnostics().stream().map(Diagnostic::getMessage).toList();
    }

    @Test
    void elementsMapToTheTagsOfTheSpecification() throws Exception {
        ElAnalyzer analyzer = analyzer("""
                <span jsf:rendered="true">x</span>
                <input jsf:value="a"/>
                <input type="email" jsf:value="b"/>
                <input type="checkbox" jsf:value="c"/>
                <input type="button" jsf:value="d"/>
                <a jsf:action="e">x</a>
                <a jsf:outcome="f">x</a>
                <a jsf:value="g">x</a>
                <button jsf:outcome="h">x</button>
                <button jsf:id="i">x</button>
                <select multiple="multiple" jsf:value="j"/>
                <select jsf:value="k"/>
                <textarea jsf:value="l"/>
                <form jsf:id="m"/>
                """);
        assertTrue(hoverText(analyzer, "span").contains("processed as `jsf:element`"));
        // without a type, input matches no row of the table
        assertTrue(hoverText(analyzer, "input jsf:value=\"a\"").contains("`jsf:element`"));
        assertTrue(hoverText(analyzer, "input type=\"email\"").contains("`h:inputText`"));
        assertTrue(hoverText(analyzer, "input type=\"checkbox\"").contains("`h:selectBooleanCheckbox`"));
        assertTrue(hoverText(analyzer, "input type=\"button\"").contains("`h:commandButton`"));
        assertTrue(hoverText(analyzer, "a jsf:action").contains("`h:commandLink`"));
        assertTrue(hoverText(analyzer, "a jsf:outcome").contains("`h:link`"));
        assertTrue(hoverText(analyzer, "a jsf:value").contains("`h:outputLink`"));
        assertTrue(hoverText(analyzer, "button jsf:outcome").contains("`h:button`"));
        assertTrue(hoverText(analyzer, "button jsf:id").contains("`h:commandButton`"));
        assertTrue(hoverText(analyzer, "select multiple").contains("`h:selectManyListbox`"));
        assertTrue(hoverText(analyzer, "select jsf:value").contains("`h:selectOneListbox`"));
        assertTrue(hoverText(analyzer, "textarea").contains("`h:inputTextarea`"));
        assertTrue(hoverText(analyzer, "form jsf:id").contains("`h:form`"));
    }

    @Test
    void plainElementsStayMarkup() throws Exception {
        ElAnalyzer analyzer = analyzer("<span class=\"x\">x</span>");
        assertNull(hoverText(analyzer, "span"));
    }

    @Test
    void facesAttributesAreAttributesOfTheTargetTag() throws Exception {
        ElAnalyzer analyzer = analyzer("""
                <span jsf:rendered="true" title="t">x</span>
                <input type="text" jsf:value="v" value="plain"/>
                """);
        String rendered = hoverText(analyzer, "jsf:rendered");
        assertNotNull(rendered);
        assertTrue(rendered.contains("attribute of `jsf:element`"), rendered);
        assertTrue(hoverText(analyzer, "jsf:value").contains("attribute of `h:inputText`"));
        // attributes without a namespace are passed through to the markup
        assertNull(hoverText(analyzer, "title="));
        assertNull(hoverText(analyzer, "value=\"plain\""));
    }

    @Test
    void unknownFacesAttributesAreReported() throws Exception {
        List<String> messages = warnings(analyzer("""
                <span jsf:rendered="true" jsf:id="s" jsf:binding="#{b}" data-x="1">x</span>
                <input type="text" jsf:value="v" jsf:valu="w" placeholder="p"/>
                <h:outputText jsf:id="x"/>
                """));
        assertEquals(List.of("Unknown attribute 'valu' for <input>, processed as <h:inputText>",
                "Attributes in the jakarta.faces namespace are only allowed on plain HTML elements"), messages);
    }

    @Test
    void elementNameIsSetFromTheElement() throws Exception {
        assertFalse(warnings(analyzer("<section jsf:id=\"s\"/>")).stream().anyMatch(m -> m.contains("elementName")));
    }

    @Test
    void completesFacesAttributesOfTheTargetTag() throws Exception {
        ElAnalyzer analyzer = analyzer("<input type=\"text\" jsf:value=\"v\" />");
        List<String> labels = completions(analyzer, at(analyzer, " />") + 1);
        assertTrue(labels.contains("jsf:required"), labels.toString());
        assertTrue(labels.contains("jsf:converter"), labels.toString());
        assertFalse(labels.contains("jsf:value"), labels.toString());
        assertFalse(labels.contains("required"), labels.toString());
    }

    @Test
    void completesFacesAttributesOnPlainElements() throws Exception {
        ElAnalyzer analyzer = analyzer("<a href=\"x\" >x</a><div >y</div>");
        List<String> link = completions(analyzer, at(analyzer, " >x") + 1);
        // the attributes that select h:commandLink, h:outputLink and h:link
        assertTrue(link.containsAll(List.of("jsf:action", "jsf:actionListener", "jsf:outcome", "jsf:value")), link.toString());
        List<String> div = completions(analyzer, at(analyzer, " >y") + 1);
        assertTrue(div.containsAll(List.of("jsf:id", "jsf:rendered", "jsf:binding", "jsf:onclick")), div.toString());
        assertFalse(div.contains("jsf:elementName"), div.toString());
    }

    @Test
    void completingAnUndeclaredPrefixDeclaresIt() throws Exception {
        ElAnalyzer analyzer = analyzer("<div js>y</div>", "");
        int offset = at(analyzer, "js>") + 2;
        CompletionItem item = new CompletionProvider(analyzer, FacesSettings.defaults()).complete(offset).stream()
                .filter(i -> i.getLabel().equals("jsf:rendered")).findFirst().orElseThrow();
        assertEquals(" xmlns:jsf=\"jakarta.faces\"", item.getAdditionalTextEdits().get(0).getNewText());
    }

    @Test
    void componentIdIsTheFacesId() throws Exception {
        ElAnalyzer analyzer = analyzer("""
                <form jsf:id="f">
                  <input type="text" id="plain" jsf:id="name" jsf:value="v"/>
                  <label jsf:for="name">Name</label>
                  <h:message for="plain"/>
                </form>
                """);
        List<Location> targets = new DefinitionProvider(analyzer, u -> null).definition(at(analyzer, "\"name\">Name") + 2);
        assertEquals(1, targets.size(), targets.toString());
        int line = targets.get(0).getRange().getStart().getLine();
        assertEquals(2, line);
        // a plain id of a passthrough element is rendered, but does not name the component
        assertTrue(new DefinitionProvider(analyzer, u -> null).definition(at(analyzer, "\"plain\"/>") + 2).isEmpty());
    }
}
