package io.github.jungm.fls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import io.github.jungm.fls.el.ElNode;
import io.github.jungm.fls.el.ElParser;
import io.github.jungm.fls.el.ElRegion;
import io.github.jungm.fls.java.JType;
import io.github.jungm.fls.xml.XmlDocument;
import io.github.jungm.fls.xml.XmlElement;

class ParserTest {

    @Test
    void xmlTreeWithNamespaces() {
        String text = """
                <ui:composition xmlns:ui="jakarta.faces.facelets" xmlns:h="jakarta.faces.html">
                  <h:form id="f"><h:inputText value="#{a > b}"/></h:form>
                  <!-- <h:ignored/> -->
                </ui:composition>""";
        XmlDocument document = XmlDocument.parse(text);
        XmlElement root = document.root();
        assertEquals("ui:composition", root.name());
        XmlElement form = root.children().get(0);
        assertEquals("jakarta.faces.html", form.namespace());
        XmlElement input = form.children().get(0);
        assertEquals("#{a > b}", input.attributeValue("value"));
        assertTrue(input.selfClosing());
        assertEquals(2, document.allElements().size() - 1);
    }

    @Test
    void xmlRecoversFromUnterminatedTags() {
        String text = "<a xmlns:h=\"x\">\n  <h:inputText value=\n  <h:outputText value=\"ok\"/>\n</a>";
        XmlDocument document = XmlDocument.parse(text);
        XmlElement root = document.root();
        assertEquals("a", root.name());
        assertTrue(root.children().stream().anyMatch(e -> e.name().equals("h:outputText")));
    }

    @Test
    void elRegionsNestAndEscape() {
        String text = "Hi #{u.name} \\#{no} ${ {'a': 1}['a'] } #{open";
        List<ElRegion> regions = ElRegion.scan(text, 0, text.length());
        assertEquals(3, regions.size());
        assertEquals("u.name", text.substring(regions.get(0).exprStart(), regions.get(0).exprEnd()));
        assertEquals(" {'a': 1}['a'] ", text.substring(regions.get(1).exprStart(), regions.get(1).exprEnd()));
        assertTrue(!regions.get(2).closed());
    }

    @Test
    void elParsesMemberChainsAndCalls() {
        String text = "bean.items[0].name.substring(1, 2)";
        ElParser.Result result = ElParser.parse(text, 0, text.length());
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        ElNode.Call call = assertInstanceOf(ElNode.Call.class, result.root());
        ElNode.Member member = assertInstanceOf(ElNode.Member.class, call.target());
        assertEquals("substring", member.name());
        assertEquals(2, call.arguments().size());
    }

    @Test
    void elIncompleteMemberKeepsTarget() {
        String text = "userBean.current.";
        ElParser.Result result = ElParser.parse(text, 0, text.length());
        ElNode.Member member = assertInstanceOf(ElNode.Member.class, result.root());
        assertEquals("", member.name());
        assertEquals(text.length(), member.nameStart());
        assertInstanceOf(ElNode.Member.class, member.target());
    }

    @Test
    void elOperatorsAndLambdas() {
        String text = "not empty a and fn:length(b) gt 0 ? x -> x + 1 : (p, q) -> p &lt; q";
        ElParser.Result result = ElParser.parse(text, 0, text.length());
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        ElNode.Ternary ternary = assertInstanceOf(ElNode.Ternary.class, result.root());
        assertInstanceOf(ElNode.Lambda.class, ternary.consequence());
        ElNode.Lambda alternative = assertInstanceOf(ElNode.Lambda.class, ternary.alternative());
        assertEquals(2, alternative.parameters().size());
        ElNode.Binary condition = assertInstanceOf(ElNode.Binary.class, ternary.condition());
        assertEquals("and", condition.operator());
        ElNode.Binary comparison = assertInstanceOf(ElNode.Binary.class, condition.right());
        assertInstanceOf(ElNode.Function.class, comparison.left());
    }

    @Test
    void elReportsSyntaxErrors() {
        String text = "a + * b";
        ElParser.Result result = ElParser.parse(text, 0, text.length());
        assertTrue(!result.problems().isEmpty());
        assertNotNull(result.root());
    }

    @Test
    void javaTypesSubstitute() {
        JType type = JType.parse("java.util.Map<java.lang.String,java.util.List<? extends org.example.User>>");
        assertEquals("java.util.Map", type.name());
        assertEquals(2, type.arguments().size());
        JType list = type.arguments().get(1);
        assertEquals("? extends org.example.User", list.arguments().get(0).name());
        assertEquals("org.example.User", list.arguments().get(0).upperBound().name());

        JType generic = JType.parse("java.util.List<E>");
        JType substituted = generic.substitute(java.util.Map.of("E", JType.parse("org.example.User")));
        assertEquals("java.util.List<org.example.User>", substituted.toString());
        assertEquals("String[]", JType.parse("java.lang.String[]").display());
    }
}
