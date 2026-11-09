package io.github.jungm.fls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.lsp4j.SemanticTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.analysis.LineIndex;
import io.github.jungm.fls.features.SemanticTokensProvider;
import io.github.jungm.fls.project.ProjectModel;

class SemanticTokensTest {
    @TempDir
    Path project;

    private static final String PAGE = """
            <ui:composition xmlns="http://www.w3.org/1999/xhtml"
                            xmlns:h="jakarta.faces.html"
                            xmlns:f="jakarta.faces.core"
                            xmlns:ui="jakarta.faces.facelets"
                            xmlns:fn="jakarta.tags.functions"
                            xmlns:comp="jakarta.faces.composite/components">
                <h:form id="form">
                    <ui:repeat value="#{param.values}" var="row">
                        <h:outputText value="#{row.name} #{msg.welcome}"/>
                    </ui:repeat>
                    <h:outputText value="#{fn:length('abc') gt 2 and not empty facesContext ? list.stream().map(x -> x + 1) : null}"/>
                    <h:commandButton id="save"><f:ajax render="@form:out save"/></h:commandButton>
                    <comp:card title="t"></comp:card>
                    <div>#{unknown.call(1)}</div>
                </h:form>
            </ui:composition>
            """;

    private ElAnalyzer analyzer(String text) throws Exception {
        Files.createDirectories(project.resolve("src/main/webapp/WEB-INF"));
        Files.createDirectories(project.resolve("src/main/webapp/resources/components"));
        Files.createDirectories(project.resolve("src/main/resources/demo"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("src/main/webapp/WEB-INF/faces-config.xml"), """
                <faces-config><application><resource-bundle>
                  <base-name>demo.messages</base-name><var>msg</var>
                </resource-bundle></application></faces-config>""");
        Files.writeString(project.resolve("src/main/resources/demo/messages.properties"), "welcome=Hello\n");
        Files.writeString(project.resolve("src/main/webapp/resources/components/card.xhtml"), """
                <ui:component xmlns:ui="jakarta.faces.facelets" xmlns:cc="jakarta.faces.composite">
                  <cc:interface><cc:attribute name="title"/></cc:interface>
                  <cc:implementation>#{cc.attrs.title}</cc:implementation>
                </ui:component>""");
        Path page = project.resolve("src/main/webapp/page.xhtml");
        Files.writeString(page, text);
        ProjectModel model = new ProjectModel(project, null);
        model.refresh();
        return new ElAnalyzer(model, new FacesDocument(page.toUri().toString(), text, 1));
    }

    /** Decodes the relative encoding into "text:type+modifier" entries. */
    private static List<String> decode(String text, SemanticTokens tokens) {
        LineIndex lines = new LineIndex(text);
        List<Integer> data = tokens.getData();
        List<String> result = new ArrayList<>();
        int line = 0;
        int character = 0;
        for (int i = 0; i < data.size(); i += 5) {
            line += data.get(i);
            character = data.get(i) == 0 ? character + data.get(i + 1) : data.get(i + 1);
            int start = lines.offset(new org.eclipse.lsp4j.Position(line, character));
            StringBuilder entry = new StringBuilder(text.substring(start, start + data.get(i + 2)))
                    .append(':').append(SemanticTokensProvider.TOKEN_TYPES.get(data.get(i + 3)));
            int modifiers = data.get(i + 4);
            for (int bit = 0; bit < SemanticTokensProvider.TOKEN_MODIFIERS.size(); bit++) {
                if ((modifiers & (1 << bit)) != 0) {
                    entry.append('+').append(SemanticTokensProvider.TOKEN_MODIFIERS.get(bit));
                }
            }
            result.add(entry.toString());
        }
        return result;
    }

    private List<String> tokens(String text) throws Exception {
        return decode(text, new SemanticTokensProvider(analyzer(text)).full());
    }

    @Test
    void classifiesElNames() throws Exception {
        List<String> tokens = tokens(PAGE);
        assertTrue(tokens.contains("param:variable+defaultLibrary"), tokens.toString());
        assertTrue(tokens.contains("values:property"), tokens.toString());
        assertTrue(tokens.contains("row:variable"), tokens.toString());
        assertTrue(tokens.contains("msg:variable+static"), tokens.toString());
        assertTrue(tokens.contains("welcome:property+readonly"), tokens.toString());
        assertTrue(tokens.contains("fn:namespace+defaultLibrary"), tokens.toString());
        assertTrue(tokens.contains("length:function+defaultLibrary"), tokens.toString());
        assertTrue(tokens.contains("facesContext:variable+defaultLibrary"), tokens.toString());
        assertTrue(tokens.contains("stream:method"), tokens.toString());
        assertTrue(tokens.contains("x:parameter+declaration"), tokens.toString());
        assertTrue(tokens.contains("x:parameter"), tokens.toString());
        assertTrue(tokens.contains("unknown:variable"), tokens.toString());
        assertTrue(tokens.contains("call:method"), tokens.toString());
    }

    @Test
    void tokenizesKeywordsLiteralsAndOperators() throws Exception {
        List<String> tokens = tokens(PAGE);
        for (String expected : List.of("#{:operator", "}:operator", "'abc':string", "2:number", "gt:keyword", "and:keyword",
                "not:keyword", "empty:keyword", "?:operator", "->:operator", "+:operator", "null:keyword")) {
            assertTrue(tokens.contains(expected), expected + " in " + tokens);
        }
        assertFalse(tokens.stream().anyMatch(t -> t.startsWith(".:") || t.startsWith("(:")), tokens.toString());
        // the colon of fn:length is part of the function name, the ternary's is an operator
        int function = tokens.indexOf("fn:namespace+defaultLibrary");
        assertEquals("length:function+defaultLibrary", tokens.get(function + 1), tokens.toString());
        assertTrue(tokens.contains("::operator"), tokens.toString());
    }

    @Test
    void marksTagPrefixesCompositesAndIds() throws Exception {
        List<String> tokens = tokens(PAGE);
        assertTrue(tokens.contains("h:namespace+defaultLibrary"), tokens.toString());
        assertEquals(2, tokens.stream().filter("card:class"::equals).count(), tokens.toString());
        assertTrue(tokens.contains("form:variable+declaration"), tokens.toString());
        assertTrue(tokens.contains("save:variable+declaration"), tokens.toString());
        assertTrue(tokens.contains("@form:keyword"), tokens.toString());
        assertTrue(tokens.contains("out:variable"), tokens.toString());
        assertTrue(tokens.contains("save:variable"), tokens.toString());
        assertFalse(tokens.stream().anyMatch(t -> t.startsWith("div:")), tokens.toString());
    }

    @Test
    void compositeAttributes() throws Exception {
        String text = """
                <ui:component xmlns:ui="jakarta.faces.facelets" xmlns:cc="jakarta.faces.composite">
                  <cc:interface><cc:attribute name="title"/></cc:interface>
                  <cc:implementation>#{cc.attrs.title}</cc:implementation>
                </ui:component>""";
        List<String> tokens = tokens(text);
        assertTrue(tokens.contains("cc:variable+defaultLibrary"), tokens.toString());
        assertTrue(tokens.contains("attrs:property+defaultLibrary"), tokens.toString());
        assertTrue(tokens.contains("title:property"), tokens.toString());
    }

    @Test
    void splitsMultilineTokensAndHonorsRanges() throws Exception {
        String text = """
                <ui:composition xmlns:ui="jakarta.faces.facelets">
                  #{'first
                second'}
                  #{later}
                </ui:composition>""";
        ElAnalyzer analyzer = analyzer(text);
        List<String> tokens = decode(text, new SemanticTokensProvider(analyzer).full());
        assertTrue(tokens.contains("'first:string"), tokens.toString());
        assertTrue(tokens.contains("second':string"), tokens.toString());
        int later = text.indexOf("later");
        List<String> ranged = decode(text, new SemanticTokensProvider(analyzer).range(later, later + 5));
        assertEquals(List.of("later:variable"), ranged);
    }
}
