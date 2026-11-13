package io.github.jungm.fls.features;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.jungm.fls.Threads;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.index.ClientIds;
import io.github.jungm.fls.index.FaceletsIndex;
import io.github.jungm.fls.xml.XmlAttribute;
import io.github.jungm.fls.xml.XmlElement;

/**
 * Absolute client ids ({@code :form:panel:name}) across the pages the current file ends up in.
 */
final class ClientIdLookup {
    private ClientIdLookup() {
    }

    private record Cached(long version, Map<String, List<ClientIds.ClientId>> clientIds) {
    }

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> REFRESHING = ConcurrentHashMap.newKeySet();
    private static final ExecutorService REFRESH = Executors.newSingleThreadExecutor(Threads.daemon("faces-ls-client-ids"));

    /**
     * Distinct client ids with their occurrences, or empty while the index is not built. Every edit creates a new
     * index snapshot; until the client ids are recomputed for it (in the background), the previous result is used,
     * so completion does not wait for expanding hundreds of pages after each keystroke.
     */
    static Map<String, List<ClientIds.ClientId>> all(ElAnalyzer analyzer) {
        FaceletsIndex.Snapshot snapshot = analyzer.index();
        String uri = analyzer.document().uri();
        if (snapshot == null || snapshot.entry(uri) == null) {
            return Map.of();
        }
        Cached cached = CACHE.get(uri);
        if (cached != null && cached.version() == snapshot.version()) {
            return cached.clientIds();
        }
        ComponentModel components = new ComponentModel(analyzer.project());
        if (cached == null) {
            Map<String, List<ClientIds.ClientId>> computed = ClientIds.forFile(snapshot, components, uri);
            store(uri, new Cached(snapshot.version(), computed));
            return computed;
        }
        if (REFRESHING.add(uri)) {
            REFRESH.execute(() -> {
                try {
                    store(uri, new Cached(snapshot.version(), ClientIds.forFile(snapshot, components, uri)));
                } finally {
                    REFRESHING.remove(uri);
                }
            });
        }
        return cached.clientIds();
    }

    /** Keeps the result of the newest snapshot when computations for several snapshots finish out of order. */
    private static void store(String uri, Cached computed) {
        CACHE.merge(uri, computed, (previous, next) -> next.version() >= previous.version() ? next : previous);
    }

    /** Components with exactly this client id (without leading separator), one per declaring location. */
    static List<ClientIds.ClientId> find(ElAnalyzer analyzer, String clientId) {
        List<ClientIds.ClientId> occurrences = all(analyzer).getOrDefault(clientId, List.of());
        Map<String, ClientIds.ClientId> byLocation = new LinkedHashMap<>();
        for (ClientIds.ClientId occurrence : occurrences) {
            byLocation.putIfAbsent(occurrence.fileUri() + "#" + occurrence.start(), occurrence);
        }
        return new ArrayList<>(byLocation.values());
    }

    /** A component another file declares: the file and the range to show (its id, or its tag name without one). */
    record Target(String fileUri, int start, int end) {
    }

    /**
     * The components a relative expression ({@code @form:panel}, {@code @namingcontainer}, {@code panel:name}) on
     * {@code element} resolves to in the pages the file ends up in, up to {@code lastSegment}: the enclosing form or
     * naming container, which may be in a template, an including file or around a composite, followed by ids below
     * it. Expressions with other keywords are not followed.
     */
    static List<Target> relative(ElAnalyzer analyzer, XmlElement element, SearchExpressions.Expression expression,
                                 int lastSegment) {
        FaceletsIndex.Snapshot snapshot = analyzer.index();
        String uri = analyzer.document().uri();
        if (expression.absolute() || expression.segments().isEmpty() || snapshot == null || snapshot.entry(uri) == null) {
            return List.of();
        }
        List<SearchExpressions.Segment> segments = expression.segments()
                .subList(0, Math.min(lastSegment + 1, expression.segments().size()));
        SearchExpressions.Segment first = segments.get(0);
        boolean startsAtKeyword = first.isKeyword();
        if (startsAtKeyword && !first.keyword().equals("@form") && !first.keyword().equals("@namingcontainer")) {
            return List.of();
        }
        for (int i = 1; i < segments.size(); i++) {
            if (segments.get(i).isKeyword()) {
                return List.of();
            }
        }
        ComponentModel components = new ComponentModel(analyzer.project());
        Map<String, Target> targets = new LinkedHashMap<>();
        for (ClientIds.Context context : ClientIds.contexts(snapshot, components, uri, element.start())) {
            ClientIds.Component base = startsAtKeyword && first.keyword().equals("@form") ? context.form()
                    : context.namingContainer();
            if (startsAtKeyword && segments.size() == 1) {
                if (base != null) {
                    Target target = target(snapshot, base);
                    targets.putIfAbsent(target.fileUri() + "#" + target.start(), target);
                }
                continue;
            }
            if (base != null && base.clientId() == null) {
                continue;
            }
            StringBuilder id = new StringBuilder(base == null ? "" : base.clientId());
            for (int i = startsAtKeyword ? 1 : 0; i < segments.size(); i++) {
                if (!id.isEmpty()) {
                    id.append(context.separator());
                }
                id.append(segments.get(i).text());
            }
            for (ClientIds.ClientId found : all(analyzer).getOrDefault(id.toString(), List.of())) {
                if (found.pageUri().equals(context.pageUri())) {
                    targets.putIfAbsent(found.fileUri() + "#" + found.start(),
                            new Target(found.fileUri(), found.start(), found.end()));
                }
            }
        }
        return new ArrayList<>(targets.values());
    }

    private static Target target(FaceletsIndex.Snapshot snapshot, ClientIds.Component component) {
        XmlAttribute id = ComponentModel.idAttribute(component.element());
        return id != null && id.hasValue() ? new Target(component.fileUri(), id.valueStart(), id.valueEnd())
                : new Target(component.fileUri(), component.element().nameStart(), component.element().nameEnd());
    }

    /** The client id an absolute expression spells up to (and including) a segment. */
    static String clientId(SearchExpressions.Expression expression, int lastSegment) {
        StringBuilder id = new StringBuilder();
        for (int i = 0; i <= lastSegment && i < expression.segments().size(); i++) {
            SearchExpressions.Segment segment = expression.segments().get(i);
            if (segment.text().startsWith("@")) {
                return null;
            }
            if (i > 0) {
                id.append(':');
            }
            id.append(segment.text());
        }
        return id.toString();
    }
}
