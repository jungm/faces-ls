package io.github.jungm.fls.features;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.ElType;
import io.github.jungm.fls.analysis.ElVariable;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.analysis.FacesDocument.ElSite;
import io.github.jungm.fls.el.ElLexer;
import io.github.jungm.fls.el.ElNode;
import io.github.jungm.fls.el.ElRegion;
import io.github.jungm.fls.el.ElToken;
import io.github.jungm.fls.java.TypeService;
import io.github.jungm.fls.taglib.Namespaces;
import io.github.jungm.fls.xml.XmlAttribute;
import io.github.jungm.fls.xml.XmlElement;

/**
 * Semantic tokens for a Facelets document. EL expressions are tokenized completely, names by what they resolve to:
 * <ul>
 * <li>{@code variable}: tag variables, {@code ui:param}, {@code c:set}; with {@code static} for CDI and managed
 * beans and resource bundle variables, with {@code defaultLibrary} for implicit objects and {@code cc}</li>
 * <li>{@code parameter}: lambda parameters</li>
 * <li>{@code class}: {@code f:importConstants} classes; {@code enumMember} and {@code property static readonly}
 * for their constants</li>
 * <li>{@code property} (with {@code readonly} without a setter) and {@code method}; {@code deprecated} as in
 * Java</li>
 * <li>{@code namespace} and {@code function} for taglib functions ({@code fn:length})</li>
 * <li>{@code keyword}, {@code string}, {@code number}, {@code operator} for the rest, including the delimiters</li>
 * </ul>
 * Markup gets tokens only where the syntax cannot tell: tag prefixes of tag libraries ({@code namespace}),
 * composite component tag names ({@code class}), component ids and {@code widgetVar} names
 * ({@code variable declaration}) and the ids and keywords of search expressions.
 */
public final class SemanticTokensProvider {

    public static final List<String> TOKEN_TYPES = List.of("namespace", "class", "enumMember", "parameter", "variable",
            "property", "function", "method", "keyword", "string", "number", "operator");

    public static final List<String> TOKEN_MODIFIERS = List.of("declaration", "readonly", "static", "deprecated",
            "defaultLibrary");

    private static final int NAMESPACE = 0;
    private static final int CLASS = 1;
    private static final int ENUM_MEMBER = 2;
    private static final int PARAMETER = 3;
    private static final int VARIABLE = 4;
    private static final int PROPERTY = 5;
    private static final int FUNCTION = 6;
    private static final int METHOD = 7;
    private static final int KEYWORD = 8;
    private static final int STRING = 9;
    private static final int NUMBER = 10;
    private static final int OPERATOR = 11;

    private static final int DECLARATION = 1;
    private static final int READONLY = 1 << 1;
    private static final int STATIC = 1 << 2;
    private static final int DEPRECATED = 1 << 3;
    private static final int DEFAULT_LIBRARY = 1 << 4;

    /** Punctuation that only separates; it keeps the color of the surrounding EL. */
    private static final Set<String> SEPARATORS = Set.of(".", ",", ";", "(", ")", "[", "]", "{", "}");

    record Token(int start, int end, int type, int modifiers) {
    }

    private final ElAnalyzer analyzer;
    private final FacesDocument document;
    private final ComponentModel components;
    private final List<Token> tokens = new ArrayList<>();

    public SemanticTokensProvider(ElAnalyzer analyzer) {
        this.analyzer = analyzer;
        this.document = analyzer.document();
        this.components = new ComponentModel(analyzer.project());
    }

    public static SemanticTokensLegend legend() {
        return new SemanticTokensLegend(TOKEN_TYPES, TOKEN_MODIFIERS);
    }

    /** All tokens of the document. */
    public SemanticTokens full() {
        return encode(collect(0, document.text().length()));
    }

    /** Tokens overlapping {@code [from, to)}. */
    public SemanticTokens range(int from, int to) {
        return encode(collect(from, to));
    }

    List<Token> collect(int from, int to) {
        tokens.clear();
        for (XmlElement element : document.xml().allElements()) {
            if (element.end() >= from && element.start() < to) {
                markup(element);
            }
        }
        for (ElSite site : document.elSites()) {
            if (site.region().end() >= from && site.region().start() < to) {
                el(site);
            }
        }
        List<Token> result = new ArrayList<>();
        int last = -1;
        tokens.sort(Comparator.comparingInt(Token::start));
        for (Token token : tokens) {
            if (token.start() >= last && token.end() > token.start() && token.end() > from && token.start() < to) {
                result.add(token);
                last = token.end();
            }
        }
        return result;
    }

    private void add(int start, int end, int type, int modifiers) {
        tokens.add(new Token(start, end, type, modifiers));
    }

    // ---------------------------------------------------------------- markup

    private void markup(XmlElement element) {
        if (!element.prefix().isEmpty() && element.namespace() != null && !Namespaces.XHTML.equals(element.namespace())) {
            boolean composite = components.isComposite(element);
            tagName(element.nameStart(), element, composite);
            if (element.closeNameStart() >= 0 && element.closeNameEnd() - element.closeNameStart() == element.name().length()) {
                tagName(element.closeNameStart(), element, composite);
            }
        }
        for (XmlAttribute attribute : element.attributes()) {
            if (attribute.value() == null || ElRegion.containsEl(attribute.value())) {
                continue;
            }
            if (attribute.name().equals("widgetVar")
                    || attribute == ComponentModel.idAttribute(element) && components.isComponent(element)) {
                name(attribute.value(), attribute.valueStart(), VARIABLE, DECLARATION);
            } else if (SearchExpressions.isSearchAttribute(element, PassthroughElements.componentAttributeName(element, attribute))) {
                searchExpressions(attribute);
            }
        }
    }

    private void tagName(int nameStart, XmlElement element, boolean composite) {
        int prefixEnd = nameStart + element.prefix().length();
        add(nameStart, prefixEnd, NAMESPACE, Namespaces.isStandard(element.namespace()) ? DEFAULT_LIBRARY : 0);
        if (composite) {
            add(prefixEnd + 1, nameStart + element.name().length(), CLASS, 0);
        }
    }

    /** A name inside an attribute value, without surrounding whitespace. */
    private void name(String value, int valueStart, int type, int modifiers) {
        int start = 0;
        int end = value.length();
        while (start < end && Character.isWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        add(valueStart + start, valueStart + end, type, modifiers);
    }

    private void searchExpressions(XmlAttribute attribute) {
        for (SearchExpressions.Expression expression : SearchExpressions.parse(attribute.value(), attribute.valueStart())) {
            for (SearchExpressions.Segment segment : expression.segments()) {
                if (!segment.isKeyword()) {
                    add(segment.start(), segment.end(), VARIABLE, 0);
                    continue;
                }
                String keyword = segment.keyword();
                add(segment.start(), segment.start() + keyword.length(), KEYWORD, 0);
                String argument = segment.argument();
                if (!argument.isEmpty()) {
                    int argumentStart = segment.text().indexOf(argument, keyword.length());
                    boolean number = argument.chars().allMatch(Character::isDigit);
                    add(segment.start() + argumentStart, segment.start() + argumentStart + argument.length(),
                            number ? NUMBER : VARIABLE, 0);
                }
            }
        }
    }

    // ---------------------------------------------------------------- EL

    private void el(ElSite site) {
        ElRegion region = site.region();
        add(region.start(), region.exprStart(), OPERATOR, 0);
        if (region.closed()) {
            add(region.exprEnd(), region.end(), OPERATOR, 0);
        }
        ElNode root = site.parse().root();
        Map<ElNode, ElType> types = analyzer.nodeTypes(site);
        Map<String, ElVariable> scope = analyzer.scope(site, region.exprStart());
        Map<ElNode, Integer> calls = new IdentityHashMap<>();
        root.walk(node -> {
            if (node instanceof ElNode.Call call && call.target() instanceof ElNode.Member member) {
                calls.put(member, call.arguments().size());
            }
        });
        int first = tokens.size();
        new ElVisitor(site, types, scope, calls).visit(root, Set.of());

        // keywords, literals and operators from the lexer, where no name token covers them (bean.empty)
        int base = region.exprStart();
        BitSet named = new BitSet();
        for (Token token : tokens.subList(first, tokens.size())) {
            named.set(token.start() - base, token.end() - base);
        }
        root.walk(node -> {
            if (node instanceof ElNode.Function function) {
                // the colon of prefix:name
                named.set(function.prefixStart() + function.prefix().length() - base);
            }
        });
        for (ElToken token : ElLexer.tokenize(document.text(), region.exprStart(), region.exprEnd())) {
            int type = switch (token.kind()) {
                case KEYWORD -> KEYWORD;
                case STRING -> STRING;
                case NUMBER -> NUMBER;
                case OPERATOR -> SEPARATORS.contains(token.text()) ? -1 : OPERATOR;
                default -> -1;
            };
            int covered = named.nextSetBit(token.start() - base);
            if (type >= 0 && (covered < 0 || covered >= token.end() - base)) {
                add(token.start(), token.end(), type, 0);
            }
        }
    }

    private final class ElVisitor {
        private final ElSite site;
        private final Map<ElNode, ElType> types;
        private final Map<String, ElVariable> scope;
        private final Map<ElNode, Integer> calls;

        ElVisitor(ElSite site, Map<ElNode, ElType> types, Map<String, ElVariable> scope, Map<ElNode, Integer> calls) {
            this.site = site;
            this.types = types;
            this.scope = scope;
            this.calls = calls;
        }

        void visit(ElNode node, Set<String> parameters) {
            if (node == null) {
                return;
            }
            switch (node) {
                case ElNode.Identifier identifier -> identifier(identifier, parameters);
                case ElNode.Member member -> {
                    visit(member.target(), parameters);
                    member(member);
                }
                case ElNode.Function function -> {
                    function(function);
                    function.arguments().forEach(argument -> visit(argument, parameters));
                }
                case ElNode.Lambda lambda -> {
                    Set<String> inner = new HashSet<>(parameters);
                    for (ElNode.Identifier parameter : lambda.parameters()) {
                        inner.add(parameter.name());
                        add(parameter.start(), parameter.end(), PARAMETER, DECLARATION);
                    }
                    visit(lambda.body(), inner);
                }
                default -> node.children().forEach(child -> visit(child, parameters));
            }
        }

        private void identifier(ElNode.Identifier identifier, Set<String> parameters) {
            if (parameters.contains(identifier.name())) {
                add(identifier.start(), identifier.end(), PARAMETER, 0);
                return;
            }
            ElVariable variable = scope.get(identifier.name());
            if (variable == null) {
                add(identifier.start(), identifier.end(), VARIABLE, 0);
                return;
            }
            switch (variable.kind()) {
                case IMPLICIT, COMPOSITE -> add(identifier.start(), identifier.end(), VARIABLE, DEFAULT_LIBRARY);
                case BEAN, BUNDLE -> add(identifier.start(), identifier.end(), VARIABLE, STATIC);
                case CONSTANTS -> add(identifier.start(), identifier.end(), CLASS, 0);
                case LAMBDA -> add(identifier.start(), identifier.end(), PARAMETER, 0);
                case TAG_VARIABLE, PARAMETER -> add(identifier.start(), identifier.end(), VARIABLE, 0);
            }
        }

        private void member(ElNode.Member member) {
            if (member.name().isEmpty()) {
                return;
            }
            Integer arguments = calls.get(member);
            ElType base = types.getOrDefault(member.target(), ElType.UNKNOWN);
            ElLookup.MemberTarget target = ElLookup.resolveMember(analyzer, base, member.name(), arguments == null ? -1 : arguments);
            int type = arguments != null ? METHOD : PROPERTY;
            int modifiers = 0;
            if (target != null) {
                if (target.method() != null) {
                    TypeService.MethodInfo method = target.method();
                    type = METHOD;
                    modifiers = (method.isStatic() ? STATIC : 0) | (method.deprecated() ? DEPRECATED : 0);
                } else if (target.property() != null) {
                    TypeService.Property property = target.property();
                    type = PROPERTY;
                    modifiers = (property.setter() == null ? READONLY : 0)
                            | (property.getter() != null && property.getter().deprecated() ? DEPRECATED : 0);
                } else if (target.field() != null) {
                    type = target.field().enumConstant() ? ENUM_MEMBER : PROPERTY;
                    modifiers = target.field().enumConstant() ? 0 : STATIC | READONLY;
                } else if (target.bundleBaseName() != null) {
                    type = PROPERTY;
                    modifiers = READONLY;
                } else if (target.compositeAttribute() == null && target.compositeContract() != null) {
                    // cc.attrs
                    modifiers = DEFAULT_LIBRARY;
                }
            }
            add(member.nameStart(), member.nameEnd(), type, modifiers);
        }

        private void function(ElNode.Function function) {
            String namespace = site.element() == null ? null : site.element().namespaceFor(function.prefix());
            int modifiers = Namespaces.isStandard(namespace) ? DEFAULT_LIBRARY : 0;
            add(function.prefixStart(), function.prefixStart() + function.prefix().length(), NAMESPACE, modifiers);
            if (!function.name().isEmpty()) {
                add(function.nameStart(), function.nameEnd(), FUNCTION, modifiers);
            }
        }
    }

    // ---------------------------------------------------------------- encoding

    /** The LSP relative encoding; tokens spanning lines are split per line. */
    private SemanticTokens encode(List<Token> list) {
        List<Integer> data = new ArrayList<>(list.size() * 5);
        String text = document.text();
        int previousLine = 0;
        int previousCharacter = 0;
        for (Token token : list) {
            int start = token.start();
            while (start < token.end()) {
                int lineEnd = text.indexOf('\n', start);
                int end = lineEnd < 0 || lineEnd >= token.end() ? token.end() : lineEnd;
                if (end > start) {
                    Position position = document.lines().position(start);
                    int deltaLine = position.getLine() - previousLine;
                    int deltaCharacter = deltaLine == 0 ? position.getCharacter() - previousCharacter : position.getCharacter();
                    data.add(deltaLine);
                    data.add(deltaCharacter);
                    data.add(end - start);
                    data.add(token.type());
                    data.add(token.modifiers());
                    previousLine = position.getLine();
                    previousCharacter = position.getCharacter();
                }
                start = end + 1;
            }
        }
        return new SemanticTokens(data);
    }
}
