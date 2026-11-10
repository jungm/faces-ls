package io.github.jungm.fls.features;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionItemTag;
import org.eclipse.lsp4j.InsertReplaceEdit;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import io.github.jungm.fls.FacesSettings;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.ElType;
import io.github.jungm.fls.analysis.ElVariable;
import io.github.jungm.fls.analysis.FaceletsTags;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.analysis.FacesDocument.ElSite;
import io.github.jungm.fls.analysis.Imports;
import io.github.jungm.fls.el.ElNode;
import io.github.jungm.fls.el.ElParser;
import io.github.jungm.fls.el.ElRegion;
import io.github.jungm.fls.index.ClientIds.ClientId;
import io.github.jungm.fls.index.ViewPaths;
import io.github.jungm.fls.java.JType;
import io.github.jungm.fls.java.TypeService;
import io.github.jungm.fls.project.PropertiesFile;
import io.github.jungm.fls.taglib.Namespaces;
import io.github.jungm.fls.taglib.TagLibrary;
import io.github.jungm.fls.taglib.TaglibRegistry;
import io.github.jungm.fls.xml.XmlAttribute;
import io.github.jungm.fls.xml.XmlElement;

/**
 * Completion for tag names, attribute names, attribute values and EL expressions.
 */
public final class CompletionProvider {
    private static final Set<String> HIDDEN_METHODS = Set.of("wait", "notify", "notifyAll", "getClass");
    private static final List<String> EL_KEYWORDS = List.of("empty", "not", "and", "or", "eq", "ne", "lt", "gt", "le", "ge",
            "div", "mod", "true", "false", "null", "instanceof");
    private static final List<String> AJAX_EVENTS = List.of("action", "valueChange", "click", "change", "blur", "focus",
            "keyup", "keydown", "keypress", "mouseover", "mouseout", "mousedown", "mouseup", "select", "dblclick", "input");
    private static final Pattern BRACKET_STRING = Pattern.compile("\\[\\s*(['\"])([^'\"]*)$");

    private final ElAnalyzer analyzer;
    private final FacesDocument document;
    private final NamespaceDeclarations namespaces;

    public CompletionProvider(ElAnalyzer analyzer, FacesSettings settings) {
        this.analyzer = analyzer;
        this.document = analyzer.document();
        this.namespaces = new NamespaceDeclarations(settings, analyzer.project(), document);
    }

    private boolean incomplete;

    /**
     * True when the result depends on more typing: tags of undeclared predefined prefixes are only offered once the
     * prefix is typed, so the client has to ask again until the tag name contains ':'.
     */
    public boolean incomplete() {
        return incomplete;
    }

    public List<CompletionItem> complete(int offset) {
        ElSite site = document.elAt(offset);
        if (site != null) {
            return completeEl(site, offset);
        }
        Cursor cursor = Cursor.at(document, offset);
        return switch (cursor.kind()) {
            case TAG_NAME -> completeTags(cursor, offset);
            case CLOSE_TAG_NAME -> completeCloseTag(cursor, offset);
            case ATTRIBUTE_NAME -> completeAttributes(cursor, offset);
            case ATTRIBUTE_VALUE -> completeAttributeValue(cursor, offset);
            default -> List.of();
        };
    }

    private TextEdit edit(int start, int end, String text) {
        return new TextEdit(document.lines().range(start, end), text);
    }

    private Either<TextEdit, InsertReplaceEdit> replacement(int start, int end, String text) {
        return Either.forLeft(edit(start, end, text));
    }

    /** An item that replaces {@code [start, end)} with {@code insert}. */
    private CompletionItem item(String label, CompletionItemKind kind, int start, int end, String insert) {
        CompletionItem item = new CompletionItem(label);
        item.setKind(kind);
        item.setTextEdit(replacement(start, end, insert));
        return item;
    }

    private static MarkupContent markdown(String text) {
        return text == null || text.isBlank() ? null : new MarkupContent(MarkupKind.MARKDOWN, text);
    }

    // ---------------------------------------------------------------- tags

    private List<CompletionItem> completeTags(Cursor cursor, int offset) {
        List<CompletionItem> items = new ArrayList<>();
        XmlElement context = cursor.element();
        // the partially typed element itself is part of the tree: look at its parent for declarations
        XmlElement scope = context != null && context.nameStart() == cursor.replaceStart() ? context.parent() : context;
        Map<String, String> prefixes = Tags.declaredPrefixes(scope == null ? context : scope);
        if (scope == null && context != null) {
            prefixes.putAll(Tags.declaredPrefixes(context));
        }
        Set<String> declaredCanonical = new HashSet<>();
        for (Map.Entry<String, String> declaration : prefixes.entrySet()) {
            declaredCanonical.add(Namespaces.canonical(declaration.getValue()));
            TagLibrary library = analyzer.project().taglibs().library(declaration.getValue());
            if (library == null) {
                continue;
            }
            for (TagLibrary.TagDef tag : library.tags().values()) {
                items.add(tagItem(declaration.getKey(), tag, library, cursor.replaceStart(), offset, null));
            }
        }
        // predefined prefixes the page does not declare yet: completing a tag adds the xmlns declaration
        String typed = cursor.prefix();
        incomplete = !typed.contains(":");
        if (!typed.isEmpty()) {
            for (NamespaceDeclarations.Predefined predefined : namespaces.available(scope == null ? context : scope).values()) {
                String prefix = predefined.prefix() + ":";
                if (!typed.startsWith(prefix) && !prefix.startsWith(typed)) {
                    continue;
                }
                TextEdit declaration = namespaces.declare(predefined.prefix(), predefined.uri());
                if (declaration == null) {
                    continue;
                }
                for (TagLibrary.TagDef tag : predefined.library().tags().values()) {
                    items.add(tagItem(predefined.prefix(), tag, predefined.library(), cursor.replaceStart(), offset, declaration));
                }
            }
        }
        return items;
    }

    private CompletionItem tagItem(String prefix, TagLibrary.TagDef tag, TagLibrary library, int start, int end, TextEdit declaration) {
        String name = prefix + ":" + tag.name();
        StringBuilder snippet = new StringBuilder(name);
        int index = 1;
        for (TagLibrary.AttributeDef attribute : tag.attributes()) {
            if (attribute.required()) {
                snippet.append(' ').append(attribute.name()).append("=\"$").append(index++).append('"');
            }
        }
        CompletionItem item = item(name, CompletionItemKind.Class, start, end, snippet.toString());
        item.setDetail(library.composite() ? "composite component" : library.namespace());
        item.setDocumentation(markdown(tag.description()));
        item.setFilterText(name);
        if (index > 1) {
            item.setInsertTextFormat(InsertTextFormat.Snippet);
        }
        if (declaration != null) {
            item.setAdditionalTextEdits(List.of(declaration));
            item.setDetail(library.namespace() + " · adds xmlns:" + prefix);
            item.setSortText("1" + name);
        } else {
            item.setSortText("0" + name);
        }
        return item;
    }

    private List<CompletionItem> completeCloseTag(Cursor cursor, int offset) {
        List<CompletionItem> items = new ArrayList<>();
        for (XmlElement open = cursor.element(); open != null; open = open.parent()) {
            if (open.closeTagStart() < 0 && !open.selfClosing() && !open.name().isEmpty()) {
                items.add(item(open.name(), CompletionItemKind.Class, cursor.replaceStart(), offset, open.name() + ">"));
                break;
            }
        }
        return items;
    }

    // ---------------------------------------------------------------- attributes

    private List<CompletionItem> completeAttributes(Cursor cursor, int offset) {
        List<CompletionItem> items = new ArrayList<>();
        XmlElement element = cursor.element();
        Set<String> present = new HashSet<>();
        for (XmlAttribute attribute : element.attributes()) {
            if (attribute.nameStart() != cursor.replaceStart()) {
                present.add(attribute.name());
            }
        }
        Tags.Resolved resolved = Tags.resolve(analyzer.project(), element);
        if (resolved.tag() != null) {
            for (TagLibrary.AttributeDef attribute : resolved.tag().attributes()) {
                if (present.contains(attribute.name())) {
                    continue;
                }
                CompletionItem item = item(attribute.name(), CompletionItemKind.Property, cursor.replaceStart(), offset,
                        attribute.name() + "=\"$1\"");
                item.setDetail((attribute.required() ? "required " : "") + (attribute.type() != null ? attribute.type() : ""));
                item.setDocumentation(markdown(attributeDoc(attribute)));
                item.setInsertTextFormat(InsertTextFormat.Snippet);
                item.setSortText((attribute.required() ? "0" : "1") + attribute.name());
                items.add(item);
            }
        }
        // declarations for the predefined prefixes that are not declared yet
        if (cursor.prefix().startsWith("x")) {
            for (NamespaceDeclarations.Predefined predefined : namespaces.available(element).values()) {
                String name = "xmlns:" + predefined.prefix();
                String text = name + "=\"" + predefined.uri() + "\"";
                CompletionItem item = item(text, CompletionItemKind.Module, cursor.replaceStart(), offset, text);
                item.setFilterText(name);
                item.setSortText("2" + name);
                items.add(item);
            }
        }
        return items;
    }

    static String attributeDoc(TagLibrary.AttributeDef attribute) {
        StringBuilder text = new StringBuilder();
        if (attribute.required()) {
            text.append("**required**  \n");
        }
        if (attribute.type() != null) {
            text.append("Type: `").append(attribute.type()).append("`  \n");
        }
        if (attribute.methodSignature() != null) {
            text.append("Method: `").append(attribute.methodSignature()).append("`  \n");
        }
        if (attribute.defaultValue() != null) {
            text.append("Default: `").append(attribute.defaultValue()).append("`  \n");
        }
        if (attribute.description() != null) {
            text.append('\n').append(attribute.description());
        }
        return text.toString();
    }

    private List<CompletionItem> completeAttributeValue(Cursor cursor, int offset) {
        List<CompletionItem> items = new ArrayList<>();
        XmlAttribute attribute = cursor.attribute();
        XmlElement element = cursor.element();
        String name = attribute.name();
        int start = attribute.valueStart();
        String prefix = cursor.prefix();
        String namespace = Namespaces.canonical(element.namespace());
        String local = element.localName();

        if (name.equals("xmlns") || name.startsWith("xmlns:")) {
            Set<String> uris = new LinkedHashSet<>();
            for (TagLibrary library : TaglibRegistry.standardLibraries()) {
                uris.add(Tags.preferredUri(analyzer.project(), Namespaces.canonical(library.namespace())));
            }
            uris.add(Namespaces.XHTML);
            for (TagLibrary library : analyzer.project().taglibs().projectLibraries()) {
                uris.add(library.namespace());
            }
            for (String libraryName : analyzer.project().taglibs().compositeLibraryNames()) {
                uris.add((analyzer.project().facesPackage().equals("jakarta") ? "jakarta.faces.composite/"
                        : "http://xmlns.jcp.org/jsf/composite/") + libraryName);
            }
            for (String uri : uris) {
                items.add(valueItem(uri, start, offset, CompletionItemKind.Module, null));
            }
            return items;
        }

        if (FaceletsTags.isViewReference(element, name)) {
            for (String path : ViewPaths.includable(analyzer.project(), analyzer.index(), analyzer.document().uri())) {
                items.add(valueItem(path, start, offset, CompletionItemKind.File, null));
            }
            return items;
        }

        if (SearchExpressions.isSearchAttribute(element, name)) {
            return searchExpressionItems(element, start, prefix, offset);
        }

        if (Namespaces.CORE.equals(namespace) && local.equals("ajax") && name.equals("event")) {
            for (String event : AJAX_EVENTS) {
                items.add(valueItem(event, start, offset, CompletionItemKind.EnumMember, null));
            }
            return items;
        }

        if (Tags.namesJavaType(analyzer.imports(), element, name)) {
            for (String fqn : analyzer.types().findTypes(prefix, 60)) {
                items.add(valueItem(fqn, start, offset, CompletionItemKind.Class, null));
            }
            return items;
        }

        Tags.Resolved resolved = Tags.resolve(analyzer.project(), element);
        TagLibrary.AttributeDef definition = resolved.tag() == null ? null : resolved.tag().attribute(name);
        if (definition != null && definition.type() != null
                && (definition.type().equals("boolean") || definition.type().equals("java.lang.Boolean"))) {
            items.add(valueItem("true", start, offset, CompletionItemKind.Value, null));
            items.add(valueItem("false", start, offset, CompletionItemKind.Value, null));
        }
        if (prefix.isEmpty()) {
            CompletionItem el = item("#{…}", CompletionItemKind.Snippet, start, offset, "#{$1}");
            el.setInsertTextFormat(InsertTextFormat.Snippet);
            el.setFilterText("#{");
            items.add(el);
        }
        return items;
    }

    private static final List<String[]> SEARCH_KEYWORDS = List.of(
            new String[] {"@this", "the component itself (for behaviors: the component they are attached to)"},
            new String[] {"@form", "the closest form"},
            new String[] {"@parent", "the parent component"},
            new String[] {"@namingcontainer", "the closest naming container"},
            new String[] {"@composite", "the closest composite component"},
            new String[] {"@child(${1:0})", "the n-th child component"},
            new String[] {"@next", "the next sibling component"},
            new String[] {"@previous", "the previous sibling component"},
            new String[] {"@root", "the view root"},
            new String[] {"@all", "the whole view"},
            new String[] {"@none", "no component"},
            new String[] {"@id(${1})", "all components with this id"},
            new String[] {"@widgetVar(${1})", "the component with this widgetVar (PrimeFaces)"});

    /** Keywords, ids and widgetVar names for a search expression (e.g. {@code update="@form:@child(0) msgs"}). */
    private List<CompletionItem> searchExpressionItems(XmlElement element, int valueStart, String prefix, int offset) {
        List<CompletionItem> items = new ArrayList<>();
        int expressionStart = Math.max(prefix.lastIndexOf(' '), prefix.lastIndexOf(',')) + 1;
        String expression = prefix.substring(expressionStart);
        int open = expression.lastIndexOf('(');
        if (open >= 0 && expression.indexOf(')', open) < 0) {
            // inside @id(...) or @widgetVar(...)
            boolean widgetVar = expression.substring(0, open).endsWith("@widgetVar");
            int argumentStart = valueStart + expressionStart + open + 1;
            for (XmlElement candidate : document.xml().allElements()) {
                String value = widgetVar ? candidate.attributeValue("widgetVar") : SearchExpressions.idOf(candidate);
                if (ElRegion.literal(value) != null) {
                    items.add(valueItem(value, argumentStart, offset, CompletionItemKind.Reference, "<" + candidate.name() + ">"));
                }
            }
            return items;
        }
        int segmentStart = expression.lastIndexOf(':') + 1;
        int tokenStart = valueStart + expressionStart + segmentStart;
        String chainPrefix = expression.substring(0, segmentStart);
        for (String[] keyword : SEARCH_KEYWORDS) {
            String label = keyword[0].replaceAll("\\$\\{\\d:?([^}]*)}", "$1");
            CompletionItem item = valueItem(label.endsWith("()") ? label.replace("()", "(…)") : label, tokenStart, offset,
                    CompletionItemKind.Keyword, keyword[1]);
            item.setFilterText(chainPrefix + label);
            if (keyword[0].contains("${")) {
                item.setInsertTextFormat(InsertTextFormat.Snippet);
                item.setTextEdit(replacement(tokenStart, offset, keyword[0]));
            }
            item.setSortText("1" + label);
            items.add(item);
        }
        for (XmlElement candidate : document.xml().allElements()) {
            String id = SearchExpressions.idOf(candidate);
            if (ElRegion.literal(id) != null) {
                CompletionItem item = valueItem(id, tokenStart, offset, CompletionItemKind.Reference, "<" + candidate.name() + ">");
                item.setFilterText(chainPrefix + id);
                item.setSortText("0" + id);
                items.add(item);
            }
        }
        // absolute client ids of every page this file ends up in (through templates, includes, composites),
        // filtered by what is typed like the client's fuzzy matching, and capped
        int absoluteStart = valueStart + expressionStart;
        String typed = expression.toLowerCase(Locale.ROOT);
        int added = 0;
        for (Map.Entry<String, List<ClientId>> clientId : ClientIdLookup.all(analyzer).entrySet()) {
            ClientId first = clientId.getValue().get(0);
            if (!isSubsequence(typed, first.absolute().toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (added++ >= MAX_CLIENT_IDS) {
                incomplete = true;
                break;
            }
            long pages = clientId.getValue().stream().map(ClientId::pageUri).distinct().count();
            String label = first.absolute();
            String file = first.fileUri().substring(first.fileUri().lastIndexOf('/') + 1);
            CompletionItem item = valueItem(label, absoluteStart, offset, CompletionItemKind.Reference,
                    "<" + first.tag() + "> in " + file + (pages > 1 ? " · " + pages + " pages" : ""));
            item.setFilterText(label);
            item.setSortText("2" + label);
            items.add(item);
        }
        return items;
    }

    private static final int MAX_CLIENT_IDS = 500;

    private static boolean isSubsequence(String typed, String candidate) {
        int position = 0;
        for (int i = 0; i < typed.length(); i++) {
            position = candidate.indexOf(typed.charAt(i), position);
            if (position < 0) {
                return false;
            }
            position++;
        }
        return true;
    }

    private CompletionItem valueItem(String value, int start, int end, CompletionItemKind kind, String detail) {
        CompletionItem item = item(value, kind, start, end, value);
        item.setDetail(detail);
        return item;
    }

    // ---------------------------------------------------------------- EL

    private List<CompletionItem> completeEl(ElSite site, int offset) {
        String text = document.text();
        int exprStart = site.region().exprStart();
        String before = text.substring(exprStart, offset);

        Matcher bracket = BRACKET_STRING.matcher(before);
        if (bracket.find()) {
            int bracketOffset = exprStart + bracket.start();
            ElParser.Result targetParse = ElParser.parse(text, exprStart, bracketOffset);
            ElNode target = rightmost(targetParse.root(), bracketOffset);
            if (target != null) {
                Map<String, ElVariable> scope = analyzer.scope(site, offset);
                ElType type = analyzer.eval(target, scope, null);
                int keyStart = exprStart + bracket.start(2);
                return keyItems(type, keyStart, offset);
            }
        }

        ElParser.Result parse = ElParser.parse(text, exprStart, offset);
        Map<String, ElVariable> scope = analyzer.scope(site, offset);
        ElNode[] found = new ElNode[1];
        parse.root().walk(node -> {
            if (node instanceof ElNode.Member member && member.nameStart() <= offset && offset <= member.nameEnd()) {
                found[0] = member;
            } else if (node instanceof ElNode.Function function && function.nameStart() <= offset && offset <= function.nameEnd()) {
                found[0] = function;
            } else if (node instanceof ElNode.Identifier identifier && identifier.end() == offset && found[0] == null) {
                found[0] = identifier;
            }
        });
        ElNode node = found[0];
        // replace the whole word under the cursor, not just the part before it
        int wordEnd = offset;
        while (wordEnd < site.region().exprEnd() && Character.isJavaIdentifierPart(text.charAt(wordEnd))) {
            wordEnd++;
        }
        if (node instanceof ElNode.Member member) {
            ElType type = analyzer.eval(member.target(), scope, null);
            return memberItems(type, member.nameStart(), wordEnd, expectsMethod(site));
        }
        if (node instanceof ElNode.Function function) {
            return functionItems(site, function.prefix(), function.prefixStart(), wordEnd);
        }
        ElNode.Identifier functionPrefix = ternaryFunctionPrefix(parse.root(), offset);
        if (functionPrefix != null) {
            List<CompletionItem> functions = functionItems(site, functionPrefix.name(), functionPrefix.start(), wordEnd);
            if (!functions.isEmpty()) {
                return functions;
            }
        }
        int start = node instanceof ElNode.Identifier identifier ? identifier.start() : offset;
        if (node == null && offset > exprStart && Character.isJavaIdentifierPart(text.charAt(offset - 1))) {
            // e.g. a keyword being typed
            start = offset;
            while (start > exprStart && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
                start--;
            }
        }
        return variableItems(site, scope, start, wordEnd);
    }

    /**
     * The parser reads {@code a ? fn:} and {@code a ? fn:na} as ternaries, as EL does once they are complete; while
     * they are typed, {@code fn} may as well be a function prefix. Returns the identifier before the colon when the
     * cursor is right after it or in the word that follows it, else null.
     */
    private ElNode.Identifier ternaryFunctionPrefix(ElNode root, int offset) {
        String text = document.text();
        ElNode.Identifier[] found = new ElNode.Identifier[1];
        root.walk(node -> {
            if (node instanceof ElNode.Ternary ternary && ternary.consequence() instanceof ElNode.Identifier prefix
                    && prefix.end() < offset && text.charAt(prefix.end()) == ':'
                    && (ternary.alternative() instanceof ElNode.Identifier || ternary.alternative() instanceof ElNode.Missing)
                    && ternary.alternative().start() == prefix.end() + 1 && ternary.alternative().end() == offset) {
                found[0] = prefix;
            }
        });
        return found[0];
    }

    private static ElNode rightmost(ElNode root, int end) {
        ElNode[] best = new ElNode[1];
        root.walk(node -> {
            if (node.end() == end && !(node instanceof ElNode.Missing)
                    && (best[0] == null || node.start() <= best[0].start())) {
                best[0] = node;
            }
        });
        return best[0];
    }

    private boolean expectsMethod(ElSite site) {
        if (site.attribute() == null) {
            return false;
        }
        Tags.Resolved resolved = Tags.resolve(analyzer.project(), site.element());
        if (resolved.tag() != null) {
            TagLibrary.AttributeDef definition = resolved.tag().attribute(site.attribute().name());
            if (definition != null && definition.methodSignature() != null) {
                return true;
            }
        }
        String name = site.attribute().name();
        return name.equals("action") || name.equals("actionListener") || name.equals("listener")
                || name.equals("valueChangeListener") || name.equals("validator");
    }

    private List<CompletionItem> variableItems(ElSite site, Map<String, ElVariable> scope, int start, int offset) {
        List<CompletionItem> items = new ArrayList<>();
        for (ElVariable variable : scope.values()) {
            CompletionItemKind kind = switch (variable.kind()) {
                case BEAN -> CompletionItemKind.Class;
                case IMPLICIT -> CompletionItemKind.Constant;
                case BUNDLE -> CompletionItemKind.Module;
                case CONSTANTS -> CompletionItemKind.Enum;
                default -> CompletionItemKind.Variable;
            };
            CompletionItem item = item(variable.name(), kind, start, offset, variable.name());
            String type = variable.type().display();
            item.setDetail((type.equals("?") ? "" : type + " · ") + variable.detail());
            item.setSortText(switch (variable.kind()) {
                case LAMBDA, TAG_VARIABLE, PARAMETER, COMPOSITE -> "0";
                case BEAN, BUNDLE, CONSTANTS -> "1";
                default -> "2";
            } + variable.name());
            items.add(item);
        }
        XmlElement context = site.element();
        if (context != null) {
            for (Map.Entry<String, String> declaration : Tags.declaredPrefixes(context).entrySet()) {
                TagLibrary library = analyzer.project().taglibs().library(declaration.getValue());
                if (library == null) {
                    continue;
                }
                addFunctionItems(items, declaration.getKey(), library, start, offset, null, "");
            }
        }
        for (Imports.Import imported : analyzer.imports().all(analyzer.document().xml())) {
            if (imported.kind() == Imports.Kind.FUNCTIONS && (context == null || context.namespaceFor(imported.var()) == null)) {
                items.addAll(importedFunctionItems(imported, start, offset));
            }
        }
        if (context != null) {
            for (NamespaceDeclarations.Predefined predefined : namespaces.available(context).values()) {
                TextEdit declaration = namespaces.declare(predefined.prefix(), predefined.uri());
                addFunctionItems(items, predefined.prefix(), predefined.library(), start, offset, declaration,
                        " · adds xmlns:" + predefined.prefix());
            }
        }
        for (String keyword : EL_KEYWORDS) {
            CompletionItem item = item(keyword, CompletionItemKind.Keyword, start, offset, keyword);
            item.setSortText("4" + keyword);
            items.add(item);
        }
        return items;
    }

    private List<CompletionItem> functionItems(ElSite site, String prefix, int start, int offset) {
        List<CompletionItem> items = new ArrayList<>();
        XmlElement context = site.element();
        String namespace = context == null ? null : context.namespaceFor(prefix);
        TagLibrary library = namespace == null ? null : analyzer.project().taglibs().library(namespace);
        TextEdit declaration = null;
        if (library == null && namespace == null) {
            NamespaceDeclarations.Predefined predefined = namespaces.available(context).get(prefix);
            if (predefined != null) {
                library = predefined.library();
                declaration = namespaces.declare(prefix, predefined.uri());
            }
        }
        if (library == null) {
            Imports.Import imported = namespace == null ? analyzer.functionImport(prefix) : null;
            if (imported != null) {
                items.addAll(importedFunctionItems(imported, start, offset));
            }
            return items;
        }
        addFunctionItems(items, prefix, library, start, offset, declaration, "");
        return items;
    }

    /**
     * {@code prefix:name(...)} items for the functions of a tag library. Editors that treat "prefix:name" as one word
     * filter by all of it, so the items carry the prefix.
     *
     * @param declaration  the xmlns declaration the items add, or null
     * @param detailSuffix appended to each function's signature in the detail
     */
    private void addFunctionItems(List<CompletionItem> items, String prefix, TagLibrary library, int start, int end,
                                  TextEdit declaration, String detailSuffix) {
        for (TagLibrary.FunctionDef function : library.functions().values()) {
            CompletionItem item = functionItem(prefix, function.name(), function.signature() + detailSuffix, start, end);
            item.setDocumentation(markdown(function.description()));
            if (declaration != null) {
                item.setAdditionalTextEdits(List.of(declaration));
            }
            items.add(item);
        }
    }

    /** A {@code prefix:name(...)} item that leaves the cursor between the parentheses. */
    private CompletionItem functionItem(String prefix, String function, String detail, int start, int end) {
        String name = prefix + ":" + function;
        CompletionItem item = item(name, CompletionItemKind.Function, start, end, name + "($1)");
        item.setDetail(detail);
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        item.setSortText("3" + name);
        return item;
    }

    /** {@code var:method(...)} items for the static methods of an {@code o:importFunctions}, one per name and arity. */
    private List<CompletionItem> importedFunctionItems(Imports.Import imported, int start, int offset) {
        List<CompletionItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TypeService.MethodInfo method : analyzer.importedFunctions(imported)) {
            if (!seen.add(method.name() + "/" + method.parameters().size())) {
                continue;
            }
            items.add(functionItem(imported.var(), method.name(),
                    method.returnType().display() + " " + method.signature() + " · " + imported.element().name(), start, offset));
        }
        return items;
    }

    private List<CompletionItem> memberItems(ElType type, int start, int offset, boolean methodExpression) {
        List<CompletionItem> items = new ArrayList<>();
        switch (type) {
            case ElType.Java java -> javaMembers(java.type(), start, offset, methodExpression, items);
            case ElType.CompositeComponent component -> {
                CompletionItem attrs = item("attrs", CompletionItemKind.Property, start, offset, "attrs");
                attrs.setDetail("composite component attributes");
                attrs.setSortText("0attrs");
                items.add(attrs);
                javaMembers(component.componentType(), start, offset, methodExpression, items);
            }
            case ElType.CompositeAttributes attributes -> {
                for (TagLibrary.AttributeDef attribute : attributes.contract().attributes()) {
                    CompletionItem item = item(attribute.name(), CompletionItemKind.Property, start, offset, attribute.name());
                    item.setDetail(attribute.type() != null ? attribute.type() : attribute.methodSignature());
                    item.setDocumentation(markdown(attribute.description()));
                    items.add(item);
                }
            }
            case ElType.Bundle bundle -> items.addAll(keyItems(bundle, start, offset));
            case ElType.Constants constants -> {
                for (TypeService.FieldInfo field : analyzer.constants(constants)) {
                    CompletionItemKind kind = field.enumConstant() ? CompletionItemKind.EnumMember : CompletionItemKind.Constant;
                    CompletionItem item = item(field.name(), kind, start, offset, field.name());
                    item.setDetail(constants.allValuesKeys().contains(field.name())
                            ? field.type().display() + " · all values" : field.type().display());
                    items.add(item);
                }
            }
            default -> {
                // nothing known
            }
        }
        return items;
    }

    private void javaMembers(JType type, int start, int offset, boolean methodExpression, List<CompletionItem> items) {
        TypeService types = analyzer.types();
        if (type.isArray() || type.isPrimitive() || types.info(type.name()) == null) {
            return;
        }
        if (types.asSupertype(type, "java.util.Map") != null) {
            // map entries are looked up by key; methods are still callable
            methodExpression = false;
        }
        TypeService.Members members = types.members(type);
        Set<String> accessorNames = new HashSet<>();
        for (TypeService.Property property : members.properties().values()) {
            if (property.getter() != null) {
                accessorNames.add(property.getter().name() + "/0");
            }
            if (property.setter() != null) {
                accessorNames.add(property.setter().name() + "/1");
            }
            if (property.name().equals("class")) {
                continue;
            }
            CompletionItem item = item(property.name(), CompletionItemKind.Property, start, offset, property.name());
            item.setDetail(property.type().display() + (property.readable() ? "" : " (write-only)"));
            item.setSortText((methodExpression ? "1" : "0") + property.name());
            if (property.getter() != null && property.getter().deprecated()) {
                item.setTags(List.of(CompletionItemTag.Deprecated));
            }
            items.add(item);
        }
        Set<String> seen = new HashSet<>();
        for (TypeService.MethodInfo method : members.methods()) {
            if (HIDDEN_METHODS.contains(method.name()) || accessorNames.contains(method.name() + "/" + method.parameters().size())) {
                continue;
            }
            String insert;
            if (methodExpression && !seen.contains(method.name())) {
                insert = method.name();
            } else if (method.parameters().isEmpty()) {
                insert = method.name() + "()";
            } else {
                insert = method.name() + "($1)";
            }
            seen.add(method.name());
            CompletionItem item = item(method.signature(), CompletionItemKind.Method, start, offset, insert);
            item.setFilterText(method.name());
            item.setDetail(method.returnType().display());
            if (insert.endsWith("($1)")) {
                item.setInsertTextFormat(InsertTextFormat.Snippet);
            }
            boolean objectMethod = method.declaringType().equals("java.lang.Object");
            item.setSortText((methodExpression ? "0" : objectMethod ? "3" : "2") + method.name());
            if (method.deprecated()) {
                item.setTags(List.of(CompletionItemTag.Deprecated));
            }
            items.add(item);
        }
    }

    private List<CompletionItem> keyItems(ElType type, int start, int offset) {
        List<CompletionItem> items = new ArrayList<>();
        if (type instanceof ElType.Bundle bundle) {
            PropertiesFile file = analyzer.project().bundle(bundle.baseName());
            if (file != null) {
                for (PropertiesFile.Entry entry : file.entries().values()) {
                    CompletionItem item = item(entry.key(), CompletionItemKind.Text, start, offset, entry.key());
                    item.setDetail(entry.value());
                    items.add(item);
                }
            }
        } else if (type instanceof ElType.CompositeAttributes attributes) {
            for (TagLibrary.AttributeDef attribute : attributes.contract().attributes()) {
                items.add(item(attribute.name(), CompletionItemKind.Property, start, offset, attribute.name()));
            }
        } else if (type instanceof ElType.Java java && analyzer.types().asSupertype(java.type(), "java.util.Map") == null) {
            for (TypeService.Property property : analyzer.types().members(java.type()).properties().values()) {
                CompletionItem item = item(property.name(), CompletionItemKind.Property, start, offset, property.name());
                item.setDetail(property.type().display());
                items.add(item);
            }
        }
        return items;
    }
}
