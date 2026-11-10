package io.github.jungm.fls.index;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jungm.fls.Threads;
import io.github.jungm.fls.analysis.FaceletsTags;
import io.github.jungm.fls.analysis.LineIndex;
import io.github.jungm.fls.el.ElRegion;
import io.github.jungm.fls.project.LibraryFiles;
import io.github.jungm.fls.project.LibraryJar;
import io.github.jungm.fls.project.WebRoots;
import io.github.jungm.fls.project.WebXml;
import io.github.jungm.fls.taglib.Namespaces;
import io.github.jungm.fls.taglib.TagLibrary;
import io.github.jungm.fls.taglib.TaglibXmlLoader;
import io.github.jungm.fls.xml.XmlDocument;
import io.github.jungm.fls.xml.XmlElement;

/**
 * All Facelets files of the workspace (web roots and {@code META-INF/resources} of every module) and of the
 * library jars on known classpaths, with the edges between them: composite component usages, {@code ui:include}
 * and templates. It answers who uses a file, which pages a file ends up in, and is the basis for client ids
 * across files.
 *
 * <p>The index is built in the background as an immutable {@link Snapshot}; a change to a single file re-parses
 * only that file and relinks only the files whose references it can affect. Files are placed below their web roots
 * by {@link WebRoots}. Open documents are indexed with their unsaved text.
 */
public final class FaceletsIndex implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(FaceletsIndex.class.getName());

    public enum Kind {
        COMPOSITE, INCLUDE, TEMPLATE
    }

    /** {@code element} in {@code from} uses {@code to}. */
    public record Edge(String from, Kind kind, XmlElement element, String to) {
    }

    /** One Facelets file. {@code webPath} is its path below its web root, e.g. {@code /WEB-INF/layout.xhtml}. */
    public record Entry(String uri, String text, XmlDocument xml, LineIndex lines, String webPath, String compositeKey,
                        Path webRoot) {
        public boolean underWebInf() {
            return webPath != null && webPath.startsWith("/WEB-INF/");
        }
    }

    /** Tag library facts the index needs: composite library of a namespace and the tags the taglib declares. */
    record Taglib(String compositeLibrary, Set<String> declaredTags) {
    }

    private final List<Path> workspaceRoots;
    private final Runnable onUpdate;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(Threads.daemon("faces-ls-index"));
    private final Set<Path> jars = ConcurrentHashMap.newKeySet();
    private final Map<String, Entry> openDocuments = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingUpdates = new ConcurrentHashMap<>();
    private volatile Snapshot snapshot;
    private ScheduledFuture<?> pendingRebuild;

    /** @param onUpdate runs after each new snapshot, e.g. to republish diagnostics */
    public FaceletsIndex(List<Path> workspaceRoots, Runnable onUpdate) {
        this.workspaceRoots = workspaceRoots;
        this.onUpdate = onUpdate;
    }

    /** The current index, or null while the first build runs. */
    public Snapshot snapshot() {
        return snapshot;
    }

    public void start() {
        scheduleRebuild(0);
    }

    /** Builds the index on the calling thread, for callers that need it immediately. */
    public void rebuildNow() {
        rebuild();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /** Adds the jars of a project's classpath; their {@code META-INF/resources} files and taglibs are indexed. */
    public void addClasspath(Collection<Path> entries) {
        boolean added = false;
        for (Path entry : entries) {
            if (LibraryFiles.isJar(entry) && jars.add(entry)) {
                added = true;
            }
        }
        if (added) {
            scheduleRebuild(500);
        }
    }

    /** A file was created, changed or deleted on disk. */
    public void fileChanged(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".taglib.xml") || name.equals("web.xml")) {
            scheduleRebuild(300);
        } else if (name.endsWith(".xhtml")) {
            String uri = file.toUri().toString();
            if (openDocuments.containsKey(uri)) {
                return;
            }
            schedule(uri, () -> updateFromDisk(file));
        }
    }

    /** An open document changed; its unsaved text replaces the file's content in the index. */
    public void documentChanged(String uri, String text, XmlDocument xml) {
        Entry previous = snapshot == null ? null : snapshot.entry(uri);
        Path path = toPath(uri);
        Entry entry = previous != null
                ? new Entry(uri, text, xml, new LineIndex(text), previous.webPath(), previous.compositeKey(), previous.webRoot())
                : describe(path, uri, text, xml);
        if (entry == null) {
            return;
        }
        openDocuments.put(uri, entry);
        schedule(uri, () -> replace(entry));
    }

    public void documentClosed(String uri) {
        openDocuments.remove(uri);
        Path path = toPath(uri);
        if (path != null) {
            schedule(uri, () -> updateFromDisk(path));
        }
    }

    private void schedule(String uri, Runnable update) {
        ScheduledFuture<?> previous = pendingUpdates.remove(uri);
        if (previous != null) {
            previous.cancel(false);
        }
        pendingUpdates.put(uri, executor.schedule(() -> {
            pendingUpdates.remove(uri);
            try {
                update.run();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "index update failed for " + uri, e);
            }
        }, 300, TimeUnit.MILLISECONDS));
    }

    private synchronized void scheduleRebuild(long delayMillis) {
        if (pendingRebuild != null) {
            pendingRebuild.cancel(false);
        }
        pendingRebuild = executor.schedule(() -> {
            try {
                rebuild();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "index build failed", e);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private static Path toPath(String uri) {
        try {
            return Path.of(URI.create(uri));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- building

    private void rebuild() {
        long start = System.nanoTime();
        Map<String, Entry> entries = new LinkedHashMap<>();
        Map<String, Taglib> taglibs = new HashMap<>();
        Map<Path, WebXml> webXmls = new HashMap<>();
        for (Path root : workspaceRoots) {
            scanDirectory(root, entries, taglibs, webXmls);
        }
        for (Path jar : jars) {
            scanJar(jar, entries, taglibs);
        }
        entries.putAll(openDocuments);
        Snapshot next = Snapshot.build(entries, taglibs, webXmls, nextVersion());
        snapshot = next;
        LOG.info(String.format("indexed %d Facelets files, %d edges, %d pages in %d ms", entries.size(),
                next.edgeCount(), next.pages().size(), (System.nanoTime() - start) / 1_000_000));
        onUpdate.run();
    }

    private long version;

    private synchronized long nextVersion() {
        return ++version;
    }

    private void replace(Entry entry) {
        Snapshot current = snapshot;
        if (current == null) {
            return;
        }
        snapshot = current.with(entry, nextVersion());
        onUpdate.run();
    }

    private void updateFromDisk(Path file) {
        Snapshot current = snapshot;
        if (current == null) {
            return;
        }
        String uri = file.toUri().toString();
        if (!Files.isRegularFile(file)) {
            snapshot = current.without(uri, nextVersion());
            onUpdate.run();
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Entry entry = describe(file, uri, text, XmlDocument.parse(text));
            if (entry != null) {
                snapshot = current.with(entry, nextVersion());
                onUpdate.run();
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "cannot read " + file, e);
        }
    }

    private void scanDirectory(Path root, Map<String, Entry> entries, Map<String, Taglib> taglibs, Map<Path, WebXml> webXmls) {
        if (!Files.isDirectory(root)) {
            return;
        }
        Map<Path, Optional<Path>> webRoots = new HashMap<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    if (!dir.equals(root) && WebRoots.isSkipped(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    String name = file.getFileName().toString();
                    try {
                        if (name.endsWith(".xhtml")) {
                            String uri = file.toUri().toString();
                            if (!openDocuments.containsKey(uri)) {
                                String text = Files.readString(file, StandardCharsets.UTF_8);
                                Path webRoot = webRoots.computeIfAbsent(file.getParent(),
                                        dir -> Optional.ofNullable(WebRoots.webRootOf(file))).orElse(null);
                                entries.put(uri, describe(webRoot, file, uri, text, XmlDocument.parse(text)));
                            }
                        } else if (name.endsWith(".taglib.xml")) {
                            try (InputStream input = Files.newInputStream(file)) {
                                addTaglib(TaglibXmlLoader.load(input, file.toString()), taglibs);
                            }
                        } else if (name.equals("web.xml") && file.getParent().getFileName().toString().equals("WEB-INF")) {
                            webXmls.put(file.toAbsolutePath().normalize().getParent().getParent(),
                                    WebXml.parse(Files.readString(file, StandardCharsets.UTF_8)));
                        }
                    } catch (IOException | RuntimeException e) {
                        LOG.log(Level.FINE, "cannot index " + file, e);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.log(Level.FINE, "cannot scan " + root, e);
        }
    }

    private static void addTaglib(TagLibrary library, Map<String, Taglib> taglibs) {
        if (library == null) {
            return;
        }
        taglibs.merge(Namespaces.canonical(library.namespace()),
                new Taglib(library.compositeLibraryName(), library.tags().keySet()),
                (a, b) -> new Taglib(a.compositeLibrary() != null ? a.compositeLibrary() : b.compositeLibrary(),
                        union(a.declaredTags(), b.declaredTags())));
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> result = new HashSet<>(a);
        result.addAll(b);
        return result;
    }

    private void scanJar(Path jar, Map<String, Entry> entries, Map<String, Taglib> taglibs) {
        LibraryJar library = LibraryJar.of(jar);
        for (String webPath : library.viewPaths()) {
            Path extracted = LibraryFiles.extract(jar, LibraryJar.META_INF_RESOURCES + webPath.substring(1));
            if (extracted == null) {
                continue;
            }
            try {
                String text = Files.readString(extracted, StandardCharsets.UTF_8);
                String uri = extracted.toUri().toString();
                entries.put(uri, new Entry(uri, text, XmlDocument.parse(text), new LineIndex(text), webPath,
                        webPath.substring(1), null));
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.FINE, "cannot index " + webPath + " in " + jar, e);
            }
        }
        for (TagLibrary taglib : library.taglibs()) {
            addTaglib(taglib, taglibs);
        }
    }

    /** Places a file below its web root ({@link WebRoots}); files outside web roots get no web path. */
    static Entry describe(Path file, String uri, String text, XmlDocument xml) {
        if (file == null) {
            return null;
        }
        return describe(WebRoots.webRootOf(file), file, uri, text, xml);
    }

    private static Entry describe(Path webRoot, Path file, String uri, String text, XmlDocument xml) {
        WebRoots.Location location = webRoot == null ? null : WebRoots.locate(webRoot, file);
        return location == null ? new Entry(uri, text, xml, new LineIndex(text), null, null, null)
                : new Entry(uri, text, xml, new LineIndex(text), location.webPath(), location.compositeKey(), location.webRoot());
    }

    // ---------------------------------------------------------------- snapshot

    /**
     * An immutable view of the index. A snapshot derived for a changed file ({@link #with}, {@link #without})
     * copies the maps of its predecessor and relinks only the files whose references can resolve differently;
     * the lists stored in a published snapshot are never modified.
     */
    public static final class Snapshot {
        private final Map<String, Entry> entries;
        private final Map<String, Taglib> taglibs;
        private final Map<Path, WebXml> webXmls;
        private final Map<String, List<String>> byWebPath;
        private final Map<String, List<String>> byCompositeKey;
        private final Map<String, List<Edge>> forward;
        private final Map<String, List<Edge>> reverse;
        /** Per file, the web paths ({@code p:}) and composite keys ({@code c:}) its references looked up. */
        private final Map<String, Set<String>> lookups;
        private final long version;
        private final Map<String, Object> cache = new ConcurrentHashMap<>();
        /** Lists this snapshot created while it is being built, which it may still modify; null once built. */
        private Set<List<?>> owned = Collections.newSetFromMap(new IdentityHashMap<>());

        private Snapshot(Map<String, Entry> entries, Map<String, Taglib> taglibs, Map<Path, WebXml> webXmls,
                         Map<String, List<String>> byWebPath, Map<String, List<String>> byCompositeKey,
                         Map<String, List<Edge>> forward, Map<String, List<Edge>> reverse,
                         Map<String, Set<String>> lookups, long version) {
            this.entries = entries;
            this.taglibs = taglibs;
            this.webXmls = webXmls;
            this.byWebPath = byWebPath;
            this.byCompositeKey = byCompositeKey;
            this.forward = forward;
            this.reverse = reverse;
            this.lookups = lookups;
            this.version = version;
        }

        static Snapshot build(Map<String, Entry> entries, Map<String, Taglib> taglibs, Map<Path, WebXml> webXmls, long version) {
            Snapshot snapshot = new Snapshot(new LinkedHashMap<>(entries), Map.copyOf(taglibs), Map.copyOf(webXmls),
                    new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), version);
            for (Entry entry : snapshot.entries.values()) {
                snapshot.addKeys(entry);
            }
            for (Entry entry : snapshot.entries.values()) {
                snapshot.link(entry);
            }
            snapshot.owned = null;
            return snapshot;
        }

        /** A copy sharing this snapshot's lists until it modifies them. */
        private Snapshot derive(long version) {
            return new Snapshot(new LinkedHashMap<>(entries), taglibs, webXmls, new HashMap<>(byWebPath),
                    new HashMap<>(byCompositeKey), new HashMap<>(forward), new HashMap<>(reverse), new HashMap<>(lookups),
                    version);
        }

        /**
         * This snapshot with {@code entry} added or replaced. When the file keeps its web root, web path and
         * composite key only its own edges change; otherwise the files that looked up its old or new keys are
         * relinked as well.
         */
        Snapshot with(Entry entry, long version) {
            Snapshot next = derive(version);
            Entry previous = next.entries.put(entry.uri(), entry);
            Set<String> relink = new LinkedHashSet<>();
            if (previous == null || !sameKeys(previous, entry)) {
                if (previous != null) {
                    next.removeKeys(previous);
                }
                next.addKeys(entry);
                Set<String> changed = keysOf(previous);
                changed.addAll(keysOf(entry));
                relink.addAll(next.lookingUp(changed));
            }
            relink.add(entry.uri());
            next.relink(relink);
            next.owned = null;
            return next;
        }

        /** This snapshot without the file {@code uri}; the files that looked up its keys are relinked. */
        Snapshot without(String uri, long version) {
            Snapshot next = derive(version);
            Entry previous = next.entries.remove(uri);
            if (previous != null) {
                next.removeKeys(previous);
                next.unlink(uri);
                Set<String> relink = next.lookingUp(keysOf(previous));
                relink.remove(uri);
                next.relink(relink);
            }
            next.owned = null;
            return next;
        }

        private static boolean sameKeys(Entry a, Entry b) {
            return Objects.equals(a.webPath(), b.webPath()) && Objects.equals(a.compositeKey(), b.compositeKey())
                    && Objects.equals(a.webRoot(), b.webRoot());
        }

        private static Set<String> keysOf(Entry entry) {
            Set<String> keys = new HashSet<>();
            if (entry != null && entry.webPath() != null) {
                keys.add("p:" + entry.webPath());
            }
            if (entry != null && entry.compositeKey() != null) {
                keys.add("c:" + entry.compositeKey());
            }
            return keys;
        }

        /** The files whose references looked up one of {@code keys}. */
        private Set<String> lookingUp(Set<String> keys) {
            Set<String> result = new LinkedHashSet<>();
            if (keys.isEmpty()) {
                return result;
            }
            for (Map.Entry<String, Set<String>> file : lookups.entrySet()) {
                for (String key : keys) {
                    if (file.getValue().contains(key)) {
                        result.add(file.getKey());
                        break;
                    }
                }
            }
            return result;
        }

        /** The list stored under {@code key}, copied first unless this snapshot created it. */
        private <T> List<T> modifiable(Map<String, List<T>> map, String key) {
            List<T> list = map.get(key);
            if (list == null || !owned.contains(list)) {
                list = list == null ? new ArrayList<>() : new ArrayList<>(list);
                owned.add(list);
                map.put(key, list);
            }
            return list;
        }

        private <T> void removeFrom(Map<String, List<T>> map, String key, Predicate<T> filter) {
            if (!map.containsKey(key)) {
                return;
            }
            List<T> list = modifiable(map, key);
            list.removeIf(filter);
            if (list.isEmpty()) {
                map.remove(key);
            }
        }

        /** Adds the file to the lookup lists, which are sorted so that ties resolve the same in every snapshot. */
        private void addKeys(Entry entry) {
            if (entry.webPath() != null) {
                insertSorted(modifiable(byWebPath, entry.webPath()), entry.uri());
            }
            if (entry.compositeKey() != null) {
                insertSorted(modifiable(byCompositeKey, entry.compositeKey()), entry.uri());
            }
        }

        private static void insertSorted(List<String> list, String uri) {
            int position = Collections.binarySearch(list, uri);
            if (position < 0) {
                list.add(-position - 1, uri);
            }
        }

        private void removeKeys(Entry entry) {
            if (entry.webPath() != null) {
                removeFrom(byWebPath, entry.webPath(), entry.uri()::equals);
            }
            if (entry.compositeKey() != null) {
                removeFrom(byCompositeKey, entry.compositeKey(), entry.uri()::equals);
            }
        }

        private void relink(Set<String> uris) {
            for (String uri : uris) {
                unlink(uri);
            }
            for (String uri : uris) {
                Entry entry = entries.get(uri);
                if (entry != null) {
                    link(entry);
                }
            }
        }

        private void unlink(String uri) {
            List<Edge> edges = forward.remove(uri);
            lookups.remove(uri);
            if (edges == null) {
                return;
            }
            for (Edge edge : edges) {
                removeFrom(reverse, edge.to(), e -> e.from().equals(uri));
            }
        }

        private void link(Entry entry) {
            List<Edge> edges = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            for (XmlElement element : entry.xml().allElements()) {
                for (String target : targets(entry, element, keys)) {
                    edges.add(new Edge(entry.uri(), kindOf(element), element, target));
                }
            }
            forward.put(entry.uri(), edges);
            lookups.put(entry.uri(), keys);
            for (Edge edge : edges) {
                modifiable(reverse, edge.to()).add(edge);
            }
        }

        public long version() {
            return version;
        }

        /**
         * Per-snapshot memo for derived data (client ids), dropped with the snapshot. The computation runs outside the
         * map, so concurrent callers never wait on one another; when two compute the same key, the first result wins.
         */
        @SuppressWarnings("unchecked")
        <T> T cached(String key, Supplier<T> compute) {
            Object cached = cache.get(key);
            if (cached == null) {
                Object computed = compute.get();
                cached = cache.putIfAbsent(key, computed);
                if (cached == null) {
                    cached = computed;
                }
            }
            return (T) cached;
        }

        private Kind kindOf(XmlElement element) {
            if (FaceletsTags.viewAttribute(element) == null) {
                return Kind.COMPOSITE;
            }
            return FaceletsTags.is(element, "include") ? Kind.INCLUDE : Kind.TEMPLATE;
        }

        /**
         * The files an element refers to (several when the same path exists in several modules). The web paths and
         * composite keys it looks up are added to {@code keys}.
         */
        List<String> targets(Entry from, XmlElement element, Set<String> keys) {
            String namespace = element.namespace();
            if (namespace == null) {
                return List.of();
            }
            String canonical = Namespaces.canonical(namespace);
            if (Namespaces.FACELETS.equals(canonical)) {
                String attribute = FaceletsTags.viewAttribute(element);
                String value = attribute == null ? null : ElRegion.literal(element.attributeValue(attribute));
                if (value == null) {
                    return List.of();
                }
                String webPath = WebRoots.resolve(from.webPath(), value);
                if (webPath == null) {
                    return List.of();
                }
                keys.add("p:" + webPath);
                return resolveWebPath(webPath, from.webRoot());
            }
            String library = Namespaces.compositeLibrary(canonical);
            if (library == null) {
                Taglib taglib = taglibs.get(canonical);
                if (taglib == null || taglib.compositeLibrary() == null || taglib.declaredTags().contains(element.localName())) {
                    return List.of();
                }
                library = taglib.compositeLibrary();
            }
            String key = library + "/" + element.localName() + ".xhtml";
            keys.add("c:" + key);
            return List.copyOf(byCompositeKey.getOrDefault(key, List.of()));
        }

        /**
         * The files at a web path in any indexed web root (workspace modules and jars); when there are several, the
         * one in {@code preferredWebRoot} if it has one.
         */
        public List<String> resolveWebPath(String webPath, Path preferredWebRoot) {
            List<String> candidates = List.copyOf(byWebPath.getOrDefault(webPath, List.of()));
            if (candidates.size() <= 1 || preferredWebRoot == null) {
                return candidates;
            }
            for (String candidate : candidates) {
                if (preferredWebRoot.equals(entries.get(candidate).webRoot())) {
                    return List.of(candidate);
                }
            }
            return candidates;
        }

        /**
         * The files a view path in {@code fromUri} refers to: absolute paths against every indexed web root
         * (workspace modules and jars), relative ones against the including file.
         */
        public List<String> resolve(String fromUri, String path) {
            Entry from = entries.get(fromUri);
            String webPath = WebRoots.resolve(from == null ? null : from.webPath(), path);
            return webPath == null ? List.of() : resolveWebPath(webPath, from == null ? null : from.webRoot());
        }

        public Entry entry(String uri) {
            return entries.get(uri);
        }

        public Collection<Entry> entries() {
            return Collections.unmodifiableCollection(entries.values());
        }

        public List<Edge> uses(String uri) {
            return Collections.unmodifiableList(forward.getOrDefault(uri, List.of()));
        }

        public List<Edge> usages(String uri) {
            return Collections.unmodifiableList(reverse.getOrDefault(uri, List.of()));
        }

        public int edgeCount() {
            int count = 0;
            for (List<Edge> edges : forward.values()) {
                count += edges.size();
            }
            return count;
        }

        /** The edge whose element is {@code element} in {@code uri}, if any. */
        public Edge edgeAt(String uri, XmlElement element) {
            for (Edge edge : uses(uri)) {
                if (edge.element() == element) {
                    return edge;
                }
            }
            return null;
        }

        /** Edges of {@code uri} at an offset (by element start tag), for documents the index has not caught up with. */
        public List<Edge> edgesNear(String uri, int offset) {
            List<Edge> result = new ArrayList<>();
            for (Edge edge : uses(uri)) {
                XmlElement element = edge.element();
                int end = element.startTagEnd() < 0 ? element.end() : element.startTagEnd();
                if (offset >= element.start() && offset <= end) {
                    result.add(edge);
                }
            }
            return result;
        }

        public boolean isPage(Entry entry) {
            return entry.webPath() != null && entry.compositeKey() == null && !entry.underWebInf()
                    && usages(entry.uri()).isEmpty();
        }

        public List<Entry> pages() {
            List<Entry> result = new ArrayList<>();
            for (Entry entry : entries.values()) {
                if (isPage(entry)) {
                    result.add(entry);
                }
            }
            return result;
        }

        /** The pages that contain the file, directly or through any chain of usages. */
        public Set<String> pagesContaining(String uri) {
            return cached("pages:" + uri, () -> {
                Set<String> pages = new LinkedHashSet<>();
                collectPages(uri, new HashSet<>(), pages);
                return pages;
            });
        }

        private void collectPages(String uri, Set<String> visited, Set<String> pages) {
            if (!visited.add(uri)) {
                return;
            }
            Entry entry = entries.get(uri);
            if (entry != null && isPage(entry)) {
                pages.add(uri);
            }
            for (Edge usage : usages(uri)) {
                collectPages(usage.from(), visited, pages);
            }
        }

        /** Files that use {@code uri} directly, with their usage edges. */
        public Map<String, List<Edge>> usersOf(String uri) {
            Map<String, List<Edge>> users = new LinkedHashMap<>();
            for (Edge usage : usages(uri)) {
                users.computeIfAbsent(usage.from(), k -> new ArrayList<>()).add(usage);
            }
            return users;
        }

        /** The client id separator configured in the web.xml of the page's module, ':' by default. */
        public char separatorFor(Entry page) {
            WebXml webXml = page.webRoot() == null ? null : webXmls.get(page.webRoot());
            return webXml == null ? ':' : webXml.separator();
        }
    }
}
