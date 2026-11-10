package io.github.jungm.fls.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.jungm.fls.analysis.LineIndex;
import io.github.jungm.fls.index.FaceletsIndex.Entry;
import io.github.jungm.fls.index.FaceletsIndex.Snapshot;
import io.github.jungm.fls.xml.XmlDocument;

/** Snapshots updated for single files link the same edges as a snapshot built from scratch. */
class IncrementalIndexTest {
    @TempDir
    Path workspace;

    private static final String NS = " xmlns:ui=\"jakarta.faces.facelets\" xmlns:comp=\"jakarta.faces.composite/comp\"";

    private Path webapp(String relative) {
        return workspace.resolve("web/src/main/webapp").resolve(relative);
    }

    private Entry entry(Path file, String body) {
        String text = "<ui:composition" + NS + ">" + body + "</ui:composition>";
        return FaceletsIndex.describe(file, file.toUri().toString(), text, XmlDocument.parse(text));
    }

    private static Map<String, Entry> entries(Snapshot snapshot) {
        Map<String, Entry> result = new LinkedHashMap<>();
        for (Entry entry : snapshot.entries()) {
            result.put(entry.uri(), entry);
        }
        return result;
    }

    /** Compares edges, usages and pages of {@code snapshot} with a full build of the same files. */
    private static void assertSameAsFullBuild(Snapshot snapshot) {
        Snapshot full = Snapshot.build(entries(snapshot), Map.of(), Map.of(), 0);
        assertEquals(full.edgeCount(), snapshot.edgeCount());
        assertEquals(uris(full.pages()), uris(snapshot.pages()));
        for (Entry entry : full.entries()) {
            String uri = entry.uri();
            assertEquals(new HashSet<>(full.uses(uri)), new HashSet<>(snapshot.uses(uri)), "uses of " + uri);
            assertEquals(new HashSet<>(full.usages(uri)), new HashSet<>(snapshot.usages(uri)), "usages of " + uri);
            assertEquals(full.pagesContaining(uri), snapshot.pagesContaining(uri), "pages containing " + uri);
            for (String path : List.of("/WEB-INF/layout.xhtml", "/WEB-INF/missing.xhtml", "/comp/box.xhtml")) {
                assertEquals(Set.copyOf(full.resolve(uri, path)), Set.copyOf(snapshot.resolve(uri, path)));
            }
        }
    }

    private static Set<String> uris(List<Entry> entries) {
        Set<String> result = new HashSet<>();
        entries.forEach(e -> result.add(e.uri()));
        return result;
    }

    @Test
    void relinksIncrementallyLikeAFullBuild() {
        Path page = webapp("index.xhtml");
        Path layout = webapp("WEB-INF/layout.xhtml");
        Path part = webapp("WEB-INF/parts/a.xhtml");
        Path box = webapp("resources/comp/box.xhtml");
        Path sharedBox = workspace.resolve("shared/src/main/resources/META-INF/resources/comp/box.xhtml");
        Path missing = webapp("WEB-INF/missing.xhtml");
        Path gone = webapp("resources/comp/gone.xhtml");

        Map<String, Entry> initial = new LinkedHashMap<>();
        for (Entry entry : List.of(
                entry(page, "<ui:decorate template=\"/WEB-INF/layout.xhtml\"><comp:box/><comp:gone/>"
                        + "<ui:include src=\"/WEB-INF/missing.xhtml\"/></ui:decorate>"),
                entry(layout, "<ui:include src=\"parts/a.xhtml\"/>"),
                entry(part, "<comp:box/>"),
                entry(box, ""),
                entry(sharedBox, "<ui:include src=\"/WEB-INF/layout.xhtml\"/>"))) {
            initial.put(entry.uri(), entry);
        }
        Snapshot snapshot = Snapshot.build(initial, Map.of(), Map.of(), 1);
        assertSameAsFullBuild(snapshot);

        List<UnaryOperator<Snapshot>> steps = new ArrayList<>();
        // an edit that keeps the web path: only the file's own edges change
        steps.add(s -> s.with(entry(page, "<ui:decorate template=\"/WEB-INF/layout.xhtml\"><comp:box/><comp:gone/>"
                + "<ui:include src=\"/WEB-INF/missing.xhtml\"/><ui:include src=\"/WEB-INF/parts/a.xhtml\"/></ui:decorate>"), 2));
        // new files that existing references were waiting for
        steps.add(s -> s.with(entry(missing, "<comp:box/>"), 3));
        steps.add(s -> s.with(entry(gone, ""), 4));
        // removing one of two files with the same composite key
        steps.add(s -> s.without(box.toUri().toString(), 5));
        // the same file under another web path
        steps.add(s -> {
            Entry current = s.entry(part.toUri().toString());
            return s.with(new Entry(current.uri(), current.text(), current.xml(), new LineIndex(current.text()),
                    "/WEB-INF/missing.xhtml", null, current.webRoot()), 6);
        });
        steps.add(s -> s.without(layout.toUri().toString(), 7));
        steps.add(s -> s.with(entry(layout, "<ui:include src=\"parts/a.xhtml\"/><comp:gone/>"), 8));
        steps.add(s -> s.without(webapp("never-indexed.xhtml").toUri().toString(), 9));

        for (UnaryOperator<Snapshot> step : steps) {
            Snapshot before = snapshot;
            Map<String, Set<FaceletsIndex.Edge>> usagesBefore = new LinkedHashMap<>();
            for (Entry entry : before.entries()) {
                usagesBefore.put(entry.uri(), new HashSet<>(before.usages(entry.uri())));
            }
            snapshot = step.apply(before);
            assertSameAsFullBuild(snapshot);
            // the previous snapshot is unchanged for its readers
            assertSameAsFullBuild(before);
            for (Entry entry : before.entries()) {
                assertEquals(usagesBefore.get(entry.uri()), new HashSet<>(before.usages(entry.uri())));
            }
        }
        assertTrue(snapshot.edgeCount() > 0);
        // references that resolved only after an incremental update
        assertTrue(snapshot.uses(page.toUri().toString()).stream().anyMatch(e -> e.to().equals(gone.toUri().toString())));
        assertTrue(snapshot.uses(page.toUri().toString()).stream().anyMatch(e -> e.to().equals(missing.toUri().toString())));
    }
}
