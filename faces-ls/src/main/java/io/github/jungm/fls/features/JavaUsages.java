package io.github.jungm.fls.features;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.eclipse.lsp4j.Range;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.ElType;
import io.github.jungm.fls.analysis.ElVariable;
import io.github.jungm.fls.analysis.FacesDocument.ElSite;
import io.github.jungm.fls.analysis.Imports;
import io.github.jungm.fls.el.ElNode;
import io.github.jungm.fls.index.FaceletsIndex;
import io.github.jungm.fls.java.JType;
import io.github.jungm.fls.java.TypeService;
import io.github.jungm.fls.taglib.TagLibrary;

import com.google.gson.JsonObject;

/**
 * Where EL in the workspace's Facelets files uses a Java member: method calls and method expressions, properties
 * through their getter or setter, imported constants, and functions ({@code o:importFunctions}, taglib functions).
 * A use counts when it resolves to the member's own declaration, as a Java call to it would.
 */
public final class JavaUsages {

    /** A Java member as faces-jdt describes it; {@code kind} is method, constructor, field or type. */
    public record Member(String declaringType, String name, String kind, int parameterCount) {
        public static Member from(JsonObject json) {
            if (json == null || !json.has("declaringType") || !json.has("name")) {
                return null;
            }
            return new Member(json.get("declaringType").getAsString(), json.get("name").getAsString(),
                    json.has("kind") ? json.get("kind").getAsString() : "method",
                    json.has("parameterCount") ? json.get("parameterCount").getAsInt() : -1);
        }

        /** The names EL can use for the member. */
        Set<String> elNames() {
            Set<String> names = new LinkedHashSet<>();
            switch (kind) {
                case "method" -> {
                    names.add(name);
                    String property = propertyName();
                    if (property != null) {
                        names.add(property);
                    }
                }
                case "field" -> names.add(name);
                default -> {
                    // constructors and types are not used in EL by name
                }
            }
            return names;
        }

        private String propertyName() {
            if (parameterCount == 0 && name.startsWith("get") && name.length() > 3) {
                return TypeService.decapitalize(name.substring(3));
            }
            if (parameterCount == 0 && name.startsWith("is") && name.length() > 2) {
                return TypeService.decapitalize(name.substring(2));
            }
            if (parameterCount == 1 && name.startsWith("set") && name.length() > 3) {
                return TypeService.decapitalize(name.substring(3));
            }
            return null;
        }

        boolean is(TypeService.MethodInfo method) {
            return method != null && kind.equals("method") && method.name().equals(name)
                    && method.parameters().size() == parameterCount && declaringType.equals(method.declaringType());
        }
    }

    private final FaceletsIndex.Snapshot index;
    private final Function<FaceletsIndex.Entry, ElAnalyzer> analyzers;

    /** @param analyzers an analyzer for an indexed file, with its project, the index and the import settings */
    public JavaUsages(FaceletsIndex.Snapshot index, Function<FaceletsIndex.Entry, ElAnalyzer> analyzers) {
        this.index = index;
        this.analyzers = analyzers;
    }

    /**
     * The ranges of the member's uses, by file; library files (from jars) are left out.
     *
     * @param libraries the tag libraries of the workspace's projects, whose functions may call the member under
     *                  another name
     */
    public Map<String, List<Range>> find(Member member, Collection<TagLibrary> libraries) {
        Map<String, List<Range>> result = new LinkedHashMap<>();
        Set<String> names = member.elNames();
        if (member.kind().equals("method")) {
            for (TagLibrary library : libraries) {
                for (TagLibrary.FunctionDef function : library.functions().values()) {
                    if (implementedBy(function, member)) {
                        names.add(function.name());
                    }
                }
            }
        }
        if (names.isEmpty()) {
            return result;
        }
        for (FaceletsIndex.Entry entry : index.entries()) {
            if (entry.webRoot() == null || names.stream().noneMatch(entry.text()::contains)) {
                continue;
            }
            List<Range> ranges = new ArrayList<>();
            ElAnalyzer analyzer = analyzers.apply(entry);
            for (ElSite site : analyzer.document().elSites()) {
                ElNode root = site.parse().root();
                root.walk(node -> {
                    int[] range = usage(analyzer, site, root, node, member, names);
                    if (range != null) {
                        ranges.add(entry.lines().range(range[0], range[1]));
                    }
                });
            }
            if (!ranges.isEmpty()) {
                result.put(entry.uri(), ranges);
            }
        }
        return result;
    }

    /** The offsets of {@code node}'s name when it uses the member, else null. */
    private static int[] usage(ElAnalyzer analyzer, ElSite site, ElNode root, ElNode node, Member member, Set<String> names) {
        if (node instanceof ElNode.Member access && names.contains(access.name())) {
            Map<String, ElVariable> scope = analyzer.scope(site, access.start());
            ElType base = analyzer.eval(access.target(), scope, null);
            ElLookup.MemberTarget target = ElLookup.resolveMember(analyzer, base, access.name(), ElLookup.argumentsOf(root, access));
            if (target != null && matches(target, member)) {
                return new int[] {access.nameStart(), access.nameEnd()};
            }
        } else if (node instanceof ElNode.Function function && member.kind().equals("method") && names.contains(function.name())
                && callsMember(analyzer, site, function, member)) {
            return new int[] {function.prefixStart(), function.nameEnd()};
        }
        return null;
    }

    private static boolean matches(ElLookup.MemberTarget target, Member member) {
        if (target.method() != null) {
            return member.is(target.method());
        }
        if (target.property() != null) {
            return member.is(target.property().getter()) || member.is(target.property().setter());
        }
        if (target.field() != null) {
            return member.kind().equals("field") && target.field().name().equals(member.name())
                    && member.declaringType().equals(target.field().declaringType());
        }
        return false;
    }

    private static boolean callsMember(ElAnalyzer analyzer, ElSite site, ElNode.Function function, Member member) {
        String namespace = site.element() == null ? null : site.element().namespaceFor(function.prefix());
        if (namespace == null) {
            Imports.Import imported = analyzer.functionImport(function.prefix());
            if (imported == null) {
                return false;
            }
            TypeService.MethodInfo method = ElAnalyzer.select(analyzer.importedFunctions(imported).stream()
                    .filter(m -> m.name().equals(function.name())).toList(), function.arguments().size());
            return member.is(method);
        }
        TagLibrary library = analyzer.project().taglibs().library(namespace);
        TagLibrary.FunctionDef definition = library == null ? null : library.functions().get(function.name());
        return definition != null && implementedBy(definition, member);
    }

    private static boolean implementedBy(TagLibrary.FunctionDef function, Member member) {
        if (function.functionClass() == null || function.signature() == null) {
            return false;
        }
        String method = function.signature().replaceFirst("\\s*\\(.*", "").replaceFirst(".*\\s", "");
        return JType.parse(function.functionClass()).name().replace('$', '.').equals(member.declaringType())
                && method.equals(member.name()) && function.parameterTypes().size() == member.parameterCount();
    }
}
