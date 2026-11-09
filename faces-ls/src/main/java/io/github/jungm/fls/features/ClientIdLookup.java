package io.github.jungm.fls.features;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.index.ClientIds;
import io.github.jungm.fls.index.FaceletsIndex;

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
    private static final ExecutorService REFRESH = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "faces-ls-client-ids");
        thread.setDaemon(true);
        return thread;
    });

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
            CACHE.put(uri, new Cached(snapshot.version(), computed));
            return computed;
        }
        if (REFRESHING.add(uri)) {
            REFRESH.execute(() -> {
                try {
                    CACHE.put(uri, new Cached(snapshot.version(), ClientIds.forFile(snapshot, components, uri)));
                } finally {
                    REFRESHING.remove(uri);
                }
            });
        }
        return cached.clientIds();
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
