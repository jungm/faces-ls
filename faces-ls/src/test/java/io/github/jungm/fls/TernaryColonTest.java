package io.github.jungm.fls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.el.ElNode;
import io.github.jungm.fls.el.ElParser;
import io.github.jungm.fls.features.CompletionProvider;
import io.github.jungm.fls.project.ProjectModel;

/**
 * A colon right after an identifier is a function's {@code prefix:name(} or a ternary's {@code ? a : b}, and while a
 * function is typed, {@code prefix:} or {@code prefix:na} without the parenthesis.
 */
class TernaryColonTest {
    @TempDir
    Path project;

    private static ElParser.Result parse(String text) {
        return ElParser.parse(text, 0, text.length());
    }

    private static List<String> problems(ElParser.Result result) {
        return result.problems().stream().map(ElParser.Problem::message).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a?b:c", "flag ? foo:bar", "empty x ? y:z", "a ? b: c", "a ? b :c", "x ? a ? b:c : d",
            "x ? a ? b : c:d", "a ? b:c.d", "a ? fn:f(1):c"})
    void ternaryBranchesNeedNoSpacesAroundTheColon(String text) {
        ElParser.Result result = parse(text);
        assertTrue(result.problems().isEmpty(), text + ": " + problems(result));
        assertInstanceOf(ElNode.Ternary.class, result.root(), text);
    }

    @Test
    void ternaryBranchesAreIdentifiers() {
        ElNode.Ternary ternary = assertInstanceOf(ElNode.Ternary.class, parse("flag ? foo:bar").root());
        assertEquals("foo", assertInstanceOf(ElNode.Identifier.class, ternary.consequence()).name());
        assertEquals("bar", assertInstanceOf(ElNode.Identifier.class, ternary.alternative()).name());
    }

    @Test
    void functionCallInTheConsequenceStillNeedsTheTernaryColon() {
        ElParser.Result result = parse("a ? b:c(1)");
        assertTrue(problems(result).contains("':' expected"), problems(result).toString());
        ElNode.Ternary ternary = assertInstanceOf(ElNode.Ternary.class, result.root());
        ElNode.Function function = assertInstanceOf(ElNode.Function.class, ternary.consequence());
        assertEquals("b", function.prefix());
        assertEquals("c", function.name());
    }

    @Test
    void halfTypedFunctionsKeepTheirPrefix() {
        ElNode.Function prefixOnly = assertInstanceOf(ElNode.Function.class, parse("fn:").root());
        assertEquals("fn", prefixOnly.prefix());
        assertEquals("", prefixOnly.name());
        ElNode.Function partial = assertInstanceOf(ElNode.Function.class, parse("fn:na").root());
        assertEquals("fn", partial.prefix());
        assertEquals("na", partial.name());
        assertTrue(parse("fn:na").problems().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"le", "eq", "empty", "not", "and", "div"})
    void halfTypedFunctionNamesMayBeKeywords(String name) {
        ElParser.Result result = parse("fn:" + name);
        ElNode.Function function = assertInstanceOf(ElNode.Function.class, result.root(), name);
        assertEquals("fn", function.prefix());
        assertEquals(name, function.name());
        assertTrue(result.problems().isEmpty(), name + ": " + problems(result));

        ElNode.Function call = assertInstanceOf(ElNode.Function.class, parse("fn:" + name + "(x)").root(), name);
        assertEquals(name, call.name());
        assertEquals(1, call.arguments().size());
    }

    @Test
    void keywordAfterTheTernaryColonStaysAnOperator() {
        for (String text : List.of("a ? b:empty(x)", "a ? b:not c", "a ? b:empty x")) {
            ElParser.Result result = parse(text);
            assertTrue(result.problems().isEmpty(), text + ": " + problems(result));
            ElNode.Ternary ternary = assertInstanceOf(ElNode.Ternary.class, result.root(), text);
            assertEquals("b", assertInstanceOf(ElNode.Identifier.class, ternary.consequence()).name(), text);
        }
    }

    @Test
    void halfTypedFunctionsOutsideAnOpenConsequence() {
        // the ternary's alternative, and brackets inside the consequence, cannot close the ternary
        ElNode.Ternary alternative = assertInstanceOf(ElNode.Ternary.class, parse("a ? b : fn:na").root());
        assertEquals("na", assertInstanceOf(ElNode.Function.class, alternative.alternative()).name());

        ElParser.Result parenthesized = parse("a ? (fn:na");
        ElNode.Ternary ternary = assertInstanceOf(ElNode.Ternary.class, parenthesized.root());
        ElNode.Parenthesized group = assertInstanceOf(ElNode.Parenthesized.class, ternary.consequence());
        assertEquals("na", assertInstanceOf(ElNode.Function.class, group.expression()).name());

        ElNode.Ternary argument = assertInstanceOf(ElNode.Ternary.class, parse("a ? bean.call(fn:").root());
        ElNode.Call call = assertInstanceOf(ElNode.Call.class, argument.consequence());
        assertEquals("fn", assertInstanceOf(ElNode.Function.class, call.arguments().get(0)).prefix());
    }

    @Test
    void halfTypedFunctionInAConsequenceIsReadAsTheTernary() {
        ElParser.Result prefixOnly = parse("a ? fn:");
        ElNode.Ternary ternary = assertInstanceOf(ElNode.Ternary.class, prefixOnly.root());
        assertEquals("fn", assertInstanceOf(ElNode.Identifier.class, ternary.consequence()).name());
        assertInstanceOf(ElNode.Missing.class, ternary.alternative());
        assertEquals(List.of("Expression expected"), problems(prefixOnly));

        ElNode.Ternary partial = assertInstanceOf(ElNode.Ternary.class, parse("a ? fn:na").root());
        assertEquals("na", assertInstanceOf(ElNode.Identifier.class, partial.alternative()).name());
    }

    // ---------------------------------------------------------------- completion

    private static final String PAGE = """
            <ui:composition xmlns="http://www.w3.org/1999/xhtml"
                            xmlns:h="jakarta.faces.html"
                            xmlns:ui="jakarta.faces.facelets"
                            xmlns:fn="jakarta.tags.functions">
                <h:outputText value="EXPR"/>
            </ui:composition>
            """;

    /** Completion at the {@code |} in the expression. */
    private List<CompletionItem> complete(String expression) throws Exception {
        int cursor = expression.indexOf('|');
        String text = PAGE.replace("EXPR", "#{" + expression.replace("|", "") + "}");
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.createDirectories(project.resolve("src/main/webapp"));
        Path page = project.resolve("src/main/webapp/page.xhtml");
        Files.writeString(page, text);
        ProjectModel model = new ProjectModel(project, null);
        model.refresh();
        ElAnalyzer analyzer = new ElAnalyzer(model, new FacesDocument(page.toUri().toString(), text, 1));
        return new CompletionProvider(analyzer, FacesSettings.defaults()).complete(text.indexOf("#{") + 2 + cursor);
    }

    private static List<String> labels(List<CompletionItem> items) {
        return items.stream().map(CompletionItem::getLabel).toList();
    }

    /** Asserts that {@code fn:length} is offered and replaces the expression from its prefix on. */
    private void assertCompletesFunctionFromPrefix(String expression) throws Exception {
        List<CompletionItem> items = complete(expression);
        CompletionItem length = items.stream().filter(i -> i.getLabel().equals("fn:length")).findFirst()
                .orElseThrow(() -> new AssertionError(expression + ": " + labels(items)));
        int prefixColumn = PAGE.lines().filter(l -> l.contains("EXPR")).findFirst().orElseThrow().indexOf("EXPR")
                + 2 + expression.indexOf("fn:");
        assertEquals(prefixColumn, length.getTextEdit().getLeft().getRange().getStart().getCharacter(), expression);
        assertEquals("fn:length($1)", length.getTextEdit().getLeft().getNewText(), expression);
    }

    @Test
    void completesFunctionsOfAHalfTypedPrefix() throws Exception {
        for (String expression : List.of("fn:|", "fn:len|", "fn:len|gth", "empty fn:len|", "a ? b : fn:len|", "a ? (fn:len|",
                "fn:le|", "a ? b : fn:le|", "empty fn:le|")) {
            assertCompletesFunctionFromPrefix(expression);
        }
    }

    @Test
    void completesFunctionsAfterAPrefixInAConsequence() throws Exception {
        for (String expression : List.of("flag ? fn:|", "flag ? fn:len|", "flag ? fn:len|gth")) {
            assertCompletesFunctionFromPrefix(expression);
        }
    }

    @Test
    void completesVariablesAfterATernaryColonWithoutFunctionPrefix() throws Exception {
        List<CompletionItem> items = complete("flag ? foo:fa|");
        assertTrue(labels(items).contains("facesContext"), labels(items).toString());
        assertTrue(labels(items).stream().noneMatch(l -> l.startsWith("foo:")), labels(items).toString());
    }
}
