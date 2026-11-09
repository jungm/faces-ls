package io.github.jungm.fls.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.jungm.fls.analysis.FacesDocument.ElSite;
import io.github.jungm.fls.el.ElNode;
import io.github.jungm.fls.el.ElRegion;
import io.github.jungm.fls.java.JType;
import io.github.jungm.fls.java.TypeService;
import io.github.jungm.fls.project.FacesConfig;
import io.github.jungm.fls.project.ProjectModel;
import io.github.jungm.fls.project.PropertiesFile;
import io.github.jungm.fls.taglib.Namespaces;
import io.github.jungm.fls.taglib.TagLibrary;
import io.github.jungm.fls.xml.XmlAttribute;
import io.github.jungm.fls.xml.XmlElement;

/**
 * Resolves EL names and types in a Facelets document: which variables are in scope at an offset (implicit
 * objects, beans, resource bundles, tag variables, lambda parameters) and what type each subexpression has.
 * Problems are reported only where the type of the base expression is actually known.
 */
public final class ElAnalyzer {

    public enum Severity {
        ERROR, WARNING, INFO
    }

    public record Problem(String message, int start, int end, Severity severity) {
    }

    private final ProjectModel project;
    private final FacesDocument document;
    private final String facesPackage;
    private Map<String, ElVariable> documentScope;
    private final Map<XmlAttribute, ElType> attributeTypes = new java.util.IdentityHashMap<>();

    private io.github.jungm.fls.index.FaceletsIndex.Snapshot index;

    /** The workspace Facelets index, or null when it is not built (yet). */
    public io.github.jungm.fls.index.FaceletsIndex.Snapshot index() {
        return index;
    }

    public ElAnalyzer withIndex(io.github.jungm.fls.index.FaceletsIndex.Snapshot snapshot) {
        this.index = snapshot;
        return this;
    }

    public ElAnalyzer(ProjectModel project, FacesDocument document) {
        this.project = project;
        this.document = document;
        this.facesPackage = project.facesPackage();
    }

    public ProjectModel project() {
        return project;
    }

    public FacesDocument document() {
        return document;
    }

    public TypeService types() {
        return project.types();
    }

    // ---------------------------------------------------------------- scope

    /** Variables visible at {@code offset} inside {@code site}, innermost declarations first. */
    public Map<String, ElVariable> scope(ElSite site, int offset) {
        Map<String, ElVariable> scope = new LinkedHashMap<>();
        if (site != null) {
            site.parse().root().walk(node -> {
                if (node instanceof ElNode.Lambda lambda && lambda.contains(offset)) {
                    for (ElNode.Identifier parameter : lambda.parameters()) {
                        scope.put(parameter.name(), new ElVariable(parameter.name(), ElType.UNKNOWN, ElVariable.Kind.LAMBDA,
                                "lambda parameter", document.uri(), parameter.start(), null));
                    }
                }
            });
            for (XmlElement ancestor = site.element(); ancestor != null; ancestor = ancestor.parent()) {
                if (ancestor == site.element() && site.attribute() != null) {
                    // an element's own attributes are evaluated outside its iteration
                    continue;
                }
                addTagVariables(ancestor, scope);
            }
        }
        for (Map.Entry<String, ElVariable> entry : documentScope().entrySet()) {
            scope.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return scope;
    }

    private void addTagVariables(XmlElement element, Map<String, ElVariable> scope) {
        XmlAttribute var = element.attribute("var");
        if (var != null && var.value() != null && !var.value().isBlank() && !isDocumentLevelDeclaration(element)) {
            ElType type = ElType.UNKNOWN;
            XmlAttribute collection = element.attribute("value") != null ? element.attribute("value") : element.attribute("items");
            if (collection != null) {
                type = elementType(typeOfAttribute(collection));
            } else if (element.attribute("begin") != null || element.attribute("end") != null) {
                type = ElType.java("java.lang.Integer");
            }
            scope.putIfAbsent(var.value().trim(), new ElVariable(var.value().trim(), type, ElVariable.Kind.TAG_VARIABLE,
                    "<" + element.name() + "> var", document.uri(), var.valueStart(), null));
        }
        XmlAttribute status = element.attribute("varStatus");
        if (status != null && status.value() != null && !status.value().isBlank()) {
            scope.putIfAbsent(status.value().trim(), new ElVariable(status.value().trim(), ElType.UNKNOWN,
                    ElVariable.Kind.TAG_VARIABLE, "<" + element.name() + "> varStatus", document.uri(), status.valueStart(), null));
        }
    }

    private static boolean isDocumentLevelDeclaration(XmlElement element) {
        String local = element.localName();
        return local.equals("loadBundle") || local.equals("set") || local.equals("importConstants");
    }

    /** Variables valid anywhere in the document. */
    private Map<String, ElVariable> documentScope() {
        if (documentScope != null) {
            return documentScope;
        }
        Map<String, ElVariable> scope = new LinkedHashMap<>();
        for (XmlElement element : document.xml().allElements()) {
            String namespace = Namespaces.canonical(element.namespace());
            String local = element.localName();
            if (Namespaces.CORE.equals(namespace) && local.equals("loadBundle")) {
                String var = element.attributeValue("var");
                String baseName = element.attributeValue("basename");
                if (var != null && baseName != null) {
                    scope.putIfAbsent(var, new ElVariable(var, new ElType.Bundle(baseName.trim()), ElVariable.Kind.BUNDLE,
                            "f:loadBundle " + baseName, document.uri(), element.attribute("var").valueStart(), null));
                }
            } else if (Namespaces.CORE.equals(namespace) && local.equals("importConstants")) {
                String type = element.attributeValue("type");
                if (type != null) {
                    String var = element.attributeValue("var");
                    String name = var != null ? var : type.substring(type.lastIndexOf('.') + 1);
                    scope.putIfAbsent(name, new ElVariable(name, new ElType.Constants(type.trim()), ElVariable.Kind.CONSTANTS,
                            "f:importConstants " + type, document.uri(), element.attribute("type").valueStart(), null));
                }
            } else if (Namespaces.FACELETS.equals(namespace) && local.equals("param")) {
                XmlAttribute name = element.attribute("name");
                if (name != null && name.value() != null) {
                    XmlAttribute value = element.attribute("value");
                    ElType type = value == null ? ElType.UNKNOWN : typeOfAttribute(value);
                    scope.putIfAbsent(name.value(), new ElVariable(name.value(), type, ElVariable.Kind.PARAMETER,
                            "ui:param", document.uri(), name.valueStart(), null));
                }
            } else if (Namespaces.JSTL_CORE.equals(namespace) && local.equals("set")) {
                XmlAttribute var = element.attribute("var");
                if (var != null && var.value() != null) {
                    XmlAttribute value = element.attribute("value");
                    ElType type = value == null ? ElType.UNKNOWN : typeOfAttribute(value);
                    scope.putIfAbsent(var.value(), new ElVariable(var.value(), type, ElVariable.Kind.PARAMETER,
                            "c:set", document.uri(), var.valueStart(), null));
                }
            }
        }
        TagLibrary.TagDef contract = document.compositeInterface();
        if (contract != null) {
            scope.putIfAbsent("cc", new ElVariable("cc", new ElType.CompositeComponent(contract,
                    JType.of(facesPackage + ".faces.component.UIComponent")), ElVariable.Kind.COMPOSITE,
                    "this composite component", null, -1, null));
        }
        for (FacesConfig.ResourceBundleVar bundle : project.facesConfig().resourceBundles()) {
            scope.putIfAbsent(bundle.var(), new ElVariable(bundle.var(), new ElType.Bundle(bundle.baseName()),
                    ElVariable.Kind.BUNDLE, "resource bundle " + bundle.baseName(), bundle.file().toUri().toString(),
                    bundle.offset(), null));
        }
        for (ProjectModel.Bean bean : project.beans()) {
            String detail = bean.origin() + (bean.scope() == null ? "" : " @" + bean.scope())
                    + (bean.library() == null ? "" : " · " + bean.library());
            scope.putIfAbsent(bean.name(), new ElVariable(bean.name(), ElType.java(bean.type()), ElVariable.Kind.BEAN, detail,
                    null, -1, bean.location()));
        }
        for (ElVariable implicit : implicitObjects()) {
            scope.putIfAbsent(implicit.name(), implicit);
        }
        documentScope = scope;
        return scope;
    }

    private List<ElVariable> implicitObjects() {
        String faces = facesPackage + ".faces.";
        String servlet = facesPackage + ".servlet.";
        String stringMap = "java.util.Map<java.lang.String,java.lang.Object>";
        String stringStringMap = "java.util.Map<java.lang.String,java.lang.String>";
        String stringArrayMap = "java.util.Map<java.lang.String,java.lang.String[]>";
        List<ElVariable> result = new ArrayList<>();
        result.add(ElVariable.implicit("facesContext", ElType.java(faces + "context.FacesContext"), "the current FacesContext"));
        result.add(ElVariable.implicit("externalContext", ElType.java(faces + "context.ExternalContext"), "the current ExternalContext"));
        result.add(ElVariable.implicit("application", ElType.java(servlet + "ServletContext"), "the ServletContext"));
        result.add(ElVariable.implicit("applicationScope", ElType.java(stringMap), "application scope attributes"));
        result.add(ElVariable.implicit("component", ElType.java(faces + "component.UIComponent"), "the component being processed"));
        result.add(ElVariable.implicit("cookie", ElType.java("java.util.Map<java.lang.String," + servlet + "http.Cookie>"), "request cookies"));
        result.add(ElVariable.implicit("flash", ElType.java(faces + "context.Flash"), "the Flash scope"));
        result.add(ElVariable.implicit("flowScope", ElType.java("java.util.Map<java.lang.Object,java.lang.Object>"), "the current flow's scope"));
        result.add(ElVariable.implicit("header", ElType.java(stringStringMap), "request headers"));
        result.add(ElVariable.implicit("headerValues", ElType.java(stringArrayMap), "request headers with all values"));
        result.add(ElVariable.implicit("initParam", ElType.java(stringStringMap), "context init parameters"));
        result.add(ElVariable.implicit("param", ElType.java(stringStringMap), "request parameters"));
        result.add(ElVariable.implicit("paramValues", ElType.java(stringArrayMap), "request parameters with all values"));
        result.add(ElVariable.implicit("request", ElType.java(servlet + "http.HttpServletRequest"), "the current request"));
        result.add(ElVariable.implicit("requestScope", ElType.java(stringMap), "request scope attributes"));
        result.add(ElVariable.implicit("resource", ElType.java(faces + "application.ResourceHandler"), "resource URLs: #{resource['library:name']}"));
        result.add(ElVariable.implicit("session", ElType.java(servlet + "http.HttpSession"), "the HTTP session"));
        result.add(ElVariable.implicit("sessionScope", ElType.java(stringMap), "session scope attributes"));
        result.add(ElVariable.implicit("view", ElType.java(faces + "component.UIViewRoot"), "the current view root"));
        result.add(ElVariable.implicit("viewScope", ElType.java(stringMap), "view scope attributes"));
        return result;
    }

    /** Fragments get variables from the including page, so unknown names are not reported there. */
    public boolean reportsUnknownVariables() {
        if (!types().available() || !project.javaModelLoaded() || !project.beansLoaded()) {
            return false;
        }
        XmlElement root = document.xml().root();
        if (root == null) {
            return false;
        }
        String namespace = Namespaces.canonical(root.namespace());
        if (Namespaces.FACELETS.equals(namespace)) {
            String local = root.localName();
            boolean fragment = local.equals("component") || local.equals("fragment")
                    || ((local.equals("composition") || local.equals("decorate")) && root.attribute("template") == null);
            if (fragment && document.compositeInterface() == null) {
                return false;
            }
        }
        // templates receive ui:param values from their clients
        for (XmlElement element : document.xml().allElements()) {
            if (element.localName().equals("insert") && Namespaces.FACELETS.equals(Namespaces.canonical(element.namespace()))) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- types

    /** The type of an attribute's value when it is a single EL expression. */
    public ElType typeOfAttribute(XmlAttribute attribute) {
        if (attribute.value() == null) {
            return ElType.UNKNOWN;
        }
        ElSite site = siteFor(attribute);
        if (site == null) {
            return attribute.value().isEmpty() ? ElType.UNKNOWN : ElType.java("java.lang.String");
        }
        if (!site.wholeValue()) {
            return ElType.java("java.lang.String");
        }
        ElType cached = attributeTypes.get(attribute);
        if (cached != null) {
            return cached;
        }
        attributeTypes.put(attribute, ElType.UNKNOWN);
        ElType type = eval(site.parse().root(), scope(site, site.region().exprStart()), null);
        attributeTypes.put(attribute, type);
        return type;
    }

    private ElSite siteFor(XmlAttribute attribute) {
        for (ElSite site : document.elSites()) {
            if (site.attribute() == attribute) {
                return site;
            }
        }
        return null;
    }

    /** The type each iteration variable takes for a collection-valued expression. */
    public ElType elementType(ElType collection) {
        if (!(collection instanceof ElType.Java java)) {
            return ElType.UNKNOWN;
        }
        JType type = java.type();
        if (type.isArray()) {
            return ElType.java(type.componentType());
        }
        if (type.name().equals("java.lang.Object")) {
            return ElType.UNKNOWN;
        }
        TypeService types = types();
        JType iterable = types.asSupertype(type, "java.lang.Iterable");
        if (iterable != null) {
            return typeArgument(iterable, 0);
        }
        JType map = types.asSupertype(type, "java.util.Map");
        if (map != null) {
            return ElType.java(new JType("java.util.Map.Entry", List.of(argument(map, 0), argument(map, 1)), 0));
        }
        JType dataModel = types.asSupertype(type, facesPackage + ".faces.model.DataModel");
        if (dataModel != null) {
            return typeArgument(dataModel, 0);
        }
        return types.info(type.name()) == null ? ElType.UNKNOWN : collection;
    }

    private static JType argument(JType type, int index) {
        return index < type.arguments().size() ? type.arguments().get(index).upperBound() : JType.OBJECT;
    }

    private static ElType typeArgument(JType type, int index) {
        JType argument = argument(type, index);
        return argument.name().equals("java.lang.Object") ? ElType.UNKNOWN : ElType.java(argument);
    }

    /**
     * Evaluates the static type of a node. When {@code problems} is not null, unresolved names are added to it.
     */
    public ElType eval(ElNode node, Map<String, ElVariable> scope, List<Problem> problems) {
        return new Evaluator(scope, problems, null).eval(node);
    }

    /** The static type of every evaluated subexpression of a site, keyed by node identity. */
    public Map<ElNode, ElType> nodeTypes(ElSite site) {
        Map<ElNode, ElType> types = new java.util.IdentityHashMap<>();
        new Evaluator(scope(site, site.region().exprStart()), null, types).eval(site.parse().root());
        return types;
    }

    private final class Evaluator {
        private final Map<String, ElVariable> scope;
        private final List<Problem> problems;
        private final boolean reportRoots;
        private final Map<ElNode, ElType> recorded;

        Evaluator(Map<String, ElVariable> scope, List<Problem> problems, Map<ElNode, ElType> recorded) {
            this.scope = scope;
            this.problems = problems;
            this.reportRoots = problems != null && reportsUnknownVariables();
            this.recorded = recorded;
        }

        private void problem(String message, int start, int end, Severity severity) {
            if (problems != null) {
                problems.add(new Problem(message, start, end, severity));
            }
        }

        ElType eval(ElNode node) {
            if (node == null) {
                return ElType.UNKNOWN;
            }
            ElType type = evalNode(node);
            if (recorded != null) {
                recorded.put(node, type);
            }
            return type;
        }

        private ElType evalNode(ElNode node) {
            return switch (node) {
                case ElNode.Identifier identifier -> identifier(identifier);
                case ElNode.Member member -> {
                    ElType base = eval(member.target());
                    yield member.name().isEmpty() ? ElType.UNKNOWN : property(base, member.name(), member.nameStart(), member.nameEnd());
                }
                case ElNode.Index index -> index(index);
                case ElNode.Call call -> call(call);
                case ElNode.Function function -> function(function);
                case ElNode.Literal literal -> switch (literal.kind()) {
                    case STRING -> ElType.java("java.lang.String");
                    case LONG -> ElType.java("java.lang.Long");
                    case DOUBLE -> ElType.java("java.lang.Double");
                    case BOOLEAN -> ElType.java("java.lang.Boolean");
                    case NULL -> ElType.UNKNOWN;
                };
                case ElNode.Unary unary -> {
                    ElType operand = eval(unary.operand());
                    yield unary.operator().equals("-") ? (operand instanceof ElType.Java ? operand : ElType.java("java.lang.Long"))
                            : ElType.java("java.lang.Boolean");
                }
                case ElNode.Binary binary -> binary(binary);
                case ElNode.Ternary ternary -> {
                    eval(ternary.condition());
                    ElType consequence = eval(ternary.consequence());
                    ElType alternative = eval(ternary.alternative());
                    yield consequence.equals(alternative) ? consequence : ElType.UNKNOWN;
                }
                case ElNode.Lambda lambda -> {
                    eval(lambda.body());
                    yield ElType.UNKNOWN;
                }
                case ElNode.Assignment assignment -> {
                    eval(assignment.target());
                    yield eval(assignment.value());
                }
                case ElNode.Sequence sequence -> {
                    ElType last = ElType.UNKNOWN;
                    for (ElNode expression : sequence.expressions()) {
                        last = eval(expression);
                    }
                    yield last;
                }
                case ElNode.ListLiteral list -> {
                    list.elements().forEach(this::eval);
                    yield ElType.java("java.util.List<java.lang.Object>");
                }
                case ElNode.SetLiteral set -> {
                    set.elements().forEach(this::eval);
                    yield ElType.java("java.util.Set<java.lang.Object>");
                }
                case ElNode.MapLiteral map -> {
                    map.keys().forEach(this::eval);
                    map.values().forEach(this::eval);
                    yield ElType.java("java.util.Map<java.lang.Object,java.lang.Object>");
                }
                case ElNode.Parenthesized parenthesized -> eval(parenthesized.expression());
                case ElNode.Missing missing -> ElType.UNKNOWN;
            };
        }

        private ElType identifier(ElNode.Identifier identifier) {
            ElVariable variable = scope.get(identifier.name());
            if (variable == null) {
                if (reportRoots) {
                    problem("Cannot resolve variable '" + identifier.name() + "'", identifier.start(), identifier.end(), Severity.WARNING);
                }
                return ElType.UNKNOWN;
            }
            return variable.type();
        }

        private ElType binary(ElNode.Binary binary) {
            ElType left = eval(binary.left());
            ElType right = eval(binary.right());
            return switch (binary.operator()) {
                case "+=" -> ElType.java("java.lang.String");
                case "+", "-", "*", "%", "mod" -> isIntegral(left) && isIntegral(right) ? ElType.java("java.lang.Long")
                        : ElType.java("java.lang.Number");
                case "/", "div" -> ElType.java("java.lang.Double");
                default -> ElType.java("java.lang.Boolean");
            };
        }

        private boolean isIntegral(ElType type) {
            if (!(type instanceof ElType.Java java)) {
                return false;
            }
            return switch (java.type().name()) {
                case "java.lang.Long", "java.lang.Integer", "java.lang.Short", "java.lang.Byte" -> true;
                default -> false;
            };
        }

        private ElType index(ElNode.Index index) {
            ElType base = eval(index.target());
            ElNode key = index.index();
            if (key instanceof ElNode.Literal literal && literal.kind() == ElNode.Literal.Kind.STRING) {
                return property(base, (String) literal.value(), literal.start() + 1, Math.max(literal.start() + 1, literal.end() - 1));
            }
            eval(key);
            if (base instanceof ElType.Java java) {
                JType type = java.type();
                if (type.isArray()) {
                    return ElType.java(type.componentType());
                }
                JType list = types().asSupertype(type, "java.util.List");
                if (list != null) {
                    return typeArgument(list, 0);
                }
                JType map = types().asSupertype(type, "java.util.Map");
                if (map != null) {
                    return typeArgument(map, 1);
                }
            }
            if (base instanceof ElType.Bundle) {
                return ElType.java("java.lang.String");
            }
            return ElType.UNKNOWN;
        }

        /** Resolves {@code base.name} (or {@code base['name']}). */
        ElType property(ElType base, String name, int start, int end) {
            switch (base) {
                case ElType.Java java -> {
                    JType type = java.type();
                    if (type.name().equals("java.lang.Object") || type.isPrimitive()) {
                        return ElType.UNKNOWN;
                    }
                    if (type.isArray()) {
                        problem("Arrays have no property '" + name + "'", start, end, Severity.WARNING);
                        return ElType.UNKNOWN;
                    }
                    TypeService types = types();
                    if (types.info(type.name()) == null) {
                        return ElType.UNKNOWN;
                    }
                    JType map = types.asSupertype(type, "java.util.Map");
                    if (map != null) {
                        return typeArgument(map, 1);
                    }
                    if (types.isSubtype(type, "java.util.ResourceBundle")) {
                        return ElType.java("java.lang.String");
                    }
                    TypeService.Members members = types.members(type);
                    TypeService.Property property = members.properties().get(name);
                    if (property != null) {
                        return property.type().name().equals("java.lang.Object") ? ElType.UNKNOWN : ElType.java(property.type());
                    }
                    List<TypeService.MethodInfo> methods = members.methods(name);
                    if (!methods.isEmpty()) {
                        return new ElType.MethodReference(methods.get(0), type);
                    }
                    problem("Cannot resolve property or method '" + name + "' in " + type.display(), start, end, Severity.WARNING);
                    return ElType.UNKNOWN;
                }
                case ElType.Bundle bundle -> {
                    PropertiesFile file = project.bundle(bundle.baseName());
                    if (file != null && !file.entries().containsKey(name)) {
                        problem("Key '" + name + "' not found in resource bundle " + bundle.baseName(), start, end, Severity.WARNING);
                    }
                    return ElType.java("java.lang.String");
                }
                case ElType.Constants constants -> {
                    TypeService.TypeInfo info = types().info(constants.fqn());
                    if (info == null) {
                        return ElType.UNKNOWN;
                    }
                    for (TypeService.FieldInfo field : info.fields()) {
                        if (field.isStatic() && field.name().equals(name)) {
                            return ElType.java(field.type());
                        }
                    }
                    problem("Cannot resolve constant '" + name + "' in " + constants.fqn(), start, end, Severity.WARNING);
                    return ElType.UNKNOWN;
                }
                case ElType.CompositeComponent component -> {
                    if (name.equals("attrs")) {
                        return new ElType.CompositeAttributes(component.contract());
                    }
                    return property(new ElType.Java(component.componentType()), name, start, end);
                }
                case ElType.CompositeAttributes attributes -> {
                    TagLibrary.AttributeDef attribute = attributes.contract().attribute(name);
                    if (attribute == null) {
                        problem("Attribute '" + name + "' is not declared in cc:interface", start, end, Severity.WARNING);
                        return ElType.UNKNOWN;
                    }
                    if (attribute.methodSignature() != null) {
                        return ElType.UNKNOWN;
                    }
                    return attribute.type() == null || attribute.type().isBlank() ? ElType.UNKNOWN : ElType.java(attribute.type().trim());
                }
                default -> {
                    return ElType.UNKNOWN;
                }
            }
        }

        private ElType call(ElNode.Call call) {
            List<ElType> arguments = new ArrayList<>();
            for (ElNode argument : call.arguments()) {
                arguments.add(eval(argument));
            }
            if (!(call.target() instanceof ElNode.Member member)) {
                eval(call.target());
                return ElType.UNKNOWN;
            }
            ElType base = eval(member.target());
            if (member.name().isEmpty()) {
                return ElType.UNKNOWN;
            }
            JType owner = switch (base) {
                case ElType.Java java -> java.type();
                case ElType.CompositeComponent component -> component.componentType();
                default -> null;
            };
            if (owner == null || owner.name().equals("java.lang.Object") || owner.isPrimitive()) {
                return ElType.UNKNOWN;
            }
            if (owner.isArray()) {
                problem("Arrays have no method '" + member.name() + "'", member.nameStart(), member.nameEnd(), Severity.WARNING);
                return ElType.UNKNOWN;
            }
            TypeService types = types();
            if (types.info(owner.name()) == null) {
                return ElType.UNKNOWN;
            }
            List<TypeService.MethodInfo> candidates = types.members(owner).methods(member.name());
            if (candidates.isEmpty()) {
                problem("Cannot resolve method '" + member.name() + "' in " + owner.display(), member.nameStart(), member.nameEnd(),
                        Severity.WARNING);
                return ElType.UNKNOWN;
            }
            TypeService.MethodInfo method = select(candidates, arguments.size());
            if (method == null) {
                problem("No overload of '" + member.name() + "' takes " + arguments.size() + " argument(s)", member.nameStart(),
                        member.nameEnd(), Severity.WARNING);
                return ElType.UNKNOWN;
            }
            JType result = method.returnType();
            if (result.isVoid() || result.name().equals("java.lang.Object") || result.isTypeVariable()) {
                return ElType.UNKNOWN;
            }
            return ElType.java(result);
        }

        private ElType function(ElNode.Function function) {
            function.arguments().forEach(this::eval);
            XmlElement context = document.xml().elementAt(function.start());
            String namespace = context == null ? null : context.namespaceFor(function.prefix());
            if (namespace == null) {
                if (problems != null) {
                    problem("Unknown namespace prefix '" + function.prefix() + "'", function.prefixStart(),
                            function.prefixStart() + function.prefix().length(), Severity.WARNING);
                }
                return ElType.UNKNOWN;
            }
            TagLibrary library = project.taglibs().library(namespace);
            if (library == null || function.name().isEmpty()) {
                return ElType.UNKNOWN;
            }
            TagLibrary.FunctionDef definition = library.functions().get(function.name());
            if (definition == null) {
                problem("Cannot resolve function '" + function.prefix() + ":" + function.name() + "'", function.nameStart(),
                        function.nameEnd(), Severity.WARNING);
                return ElType.UNKNOWN;
            }
            int expected = definition.parameterTypes().size();
            if (expected != function.arguments().size()) {
                problem("Function '" + function.prefix() + ":" + function.name() + "' takes " + expected + " argument(s)",
                        function.nameStart(), function.nameEnd(), Severity.WARNING);
            }
            JType result = JType.parse(definition.returnType());
            return result.isVoid() || result.name().equals("java.lang.Object") ? ElType.UNKNOWN : ElType.java(result);
        }
    }

    /** Picks the overload for an argument count, preferring exact arity over varargs. */
    public static TypeService.MethodInfo select(List<TypeService.MethodInfo> candidates, int argumentCount) {
        for (TypeService.MethodInfo candidate : candidates) {
            if (candidate.parameters().size() == argumentCount) {
                return candidate;
            }
        }
        for (TypeService.MethodInfo candidate : candidates) {
            if (candidate.varargs() && argumentCount >= candidate.parameters().size() - 1) {
                return candidate;
            }
        }
        return null;
    }

    /** Problems of one EL site: syntax errors plus unresolved names. */
    public List<Problem> check(ElSite site) {
        List<Problem> problems = new ArrayList<>();
        ElRegion region = site.region();
        if (!region.closed()) {
            problems.add(new Problem("Unterminated EL expression, '}' expected", region.start(), region.exprEnd(), Severity.ERROR));
        }
        for (var syntax : site.parse().problems()) {
            problems.add(new Problem(syntax.message(), syntax.start(), Math.max(syntax.end(), syntax.start() + 1), Severity.ERROR));
        }
        if (site.parse().problems().isEmpty()) {
            eval(site.parse().root(), scope(site, region.exprStart()), problems);
        }
        return problems;
    }
}
