package io.github.jungm.fls.features;

import java.util.List;
import java.util.Map;

import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.ElType;
import io.github.jungm.fls.analysis.ElVariable;
import io.github.jungm.fls.analysis.FacesDocument.ElSite;
import io.github.jungm.fls.el.ElNode;
import io.github.jungm.fls.java.JType;
import io.github.jungm.fls.java.TypeService;
import io.github.jungm.fls.taglib.TagLibrary;

/**
 * Finds the EL name under an offset and what it refers to.
 */
final class ElLookup {

    enum Kind {
        VARIABLE, MEMBER, FUNCTION, KEY
    }

    /**
     * @param base       type of the expression left of the name (members and keys)
     * @param arguments  argument count when the name is called, otherwise -1
     */
    record Hit(Kind kind, String name, int start, int end, ElVariable variable, ElType base, int arguments,
               ElNode.Function function) {
    }

    /** What a member name resolves to. Exactly one of the fields is set, or none when unresolved. */
    record MemberTarget(JType owner, TypeService.Property property, TypeService.MethodInfo method,
                        TypeService.FieldInfo field, TagLibrary.AttributeDef compositeAttribute, String bundleBaseName,
                        TagLibrary.TagDef compositeContract) {
    }

    private ElLookup() {
    }

    static Hit find(ElAnalyzer analyzer, ElSite site, int offset) {
        Hit[] hit = new Hit[1];
        ElNode root = site.parse().root();
        Map<String, ElVariable> scope = analyzer.scope(site, offset);
        root.walk(node -> {
            switch (node) {
                case ElNode.Identifier identifier when identifier.contains(offset) -> {
                    ElVariable variable = scope.get(identifier.name());
                    hit[0] = new Hit(Kind.VARIABLE, identifier.name(), identifier.start(), identifier.end(), variable, null, -1, null);
                }
                case ElNode.Member member when member.nameStart() <= offset && offset <= member.nameEnd() && !member.name().isEmpty() -> {
                    ElType base = analyzer.eval(member.target(), scope, null);
                    hit[0] = new Hit(Kind.MEMBER, member.name(), member.nameStart(), member.nameEnd(), null, base,
                            argumentsOf(root, member), null);
                }
                case ElNode.Index index when index.index() instanceof ElNode.Literal literal
                        && literal.kind() == ElNode.Literal.Kind.STRING && literal.contains(offset) -> {
                    ElType base = analyzer.eval(index.target(), scope, null);
                    hit[0] = new Hit(Kind.KEY, (String) literal.value(), literal.start(), literal.end(), null, base, -1, null);
                }
                case ElNode.Function function when offset >= function.prefixStart() && offset <= function.nameEnd() -> {
                    hit[0] = new Hit(Kind.FUNCTION, function.name(), function.prefixStart(), function.nameEnd(), null, null,
                            function.arguments().size(), function);
                }
                default -> {
                    // keep looking
                }
            }
        });
        return hit[0];
    }

    static int argumentsOf(ElNode root, ElNode.Member member) {
        int[] count = {-1};
        root.walk(node -> {
            if (node instanceof ElNode.Call call && call.target() == member) {
                count[0] = call.arguments().size();
            }
        });
        return count[0];
    }

    static MemberTarget resolveMember(ElAnalyzer analyzer, ElType base, String name, int arguments) {
        switch (base) {
            case ElType.Java java -> {
                return javaMember(analyzer, java.type(), name, arguments);
            }
            case ElType.CompositeComponent component -> {
                if (name.equals("attrs")) {
                    return new MemberTarget(null, null, null, null, null, null, component.contract());
                }
                return javaMember(analyzer, component.componentType(), name, arguments);
            }
            case ElType.CompositeAttributes attributes -> {
                TagLibrary.AttributeDef attribute = attributes.contract().attribute(name);
                return attribute == null ? null : new MemberTarget(null, null, null, null, attribute, null, attributes.contract());
            }
            case ElType.Bundle bundle -> {
                return new MemberTarget(null, null, null, null, null, bundle.baseName(), null);
            }
            case ElType.Constants constants -> {
                for (TypeService.FieldInfo field : analyzer.constants(constants)) {
                    if (field.name().equals(name)) {
                        return new MemberTarget(JType.of(constants.fqn()), null, null, field, null, null, null);
                    }
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private static MemberTarget javaMember(ElAnalyzer analyzer, JType type, String name, int arguments) {
        TypeService types = analyzer.types();
        if (type.isArray() || type.isPrimitive() || types.info(type.name()) == null) {
            return null;
        }
        TypeService.Members members = types.members(type);
        if (arguments >= 0) {
            TypeService.MethodInfo method = ElAnalyzer.select(members.methods(name), arguments);
            if (method != null) {
                return new MemberTarget(type, null, method, null, null, null, null);
            }
        }
        TypeService.Property property = members.properties().get(name);
        if (property != null) {
            return new MemberTarget(type, property, null, null, null, null, null);
        }
        List<TypeService.MethodInfo> methods = members.methods(name);
        if (!methods.isEmpty()) {
            return new MemberTarget(type, null, methods.get(0), null, null, null, null);
        }
        return null;
    }
}
