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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.jungm.fls.analysis.LineIndex;
import io.github.jungm.fls.project.LibraryFiles;
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
 * <p>The index is built in the background and replaced as a whole ({@link Snapshot}); changes to single files
 * re-parse only that file. Open documents are indexed with their unsaved text.
 */
public final class FaceletsIndex implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(FaceletsIndex.class.getName());
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("target", "build", "node_modules", "bin", "out");

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
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "faces-ls-index");
        thread.setDaemon(true);
        return thread;
    });
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
        Map<Path, String> webXmls = new HashMap<>();
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

    private void scanDirectory(Path root, Map<String, Entry> entries, Map<String, Taglib> taglibs, Map<Path, String> webXmls) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(root) && (SKIPPED_DIRECTORIES.contains(name) || name.startsWith("."))) {
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
                                Entry entry = describe(file, uri, text, XmlDocument.parse(text));
                                if (entry != null) {
                                    entries.put(uri, entry);
                                }
                            }
                        } else if (name.endsWith(".taglib.xml")) {
                            try (InputStream input = Files.newInputStream(file)) {
                                addTaglib(TaglibXmlLoader.load(input, file.toString()), taglibs);
                            }
                        } else if (name.equals("web.xml") && file.getParent().getFileName().toString().equals("WEB-INF")) {
                            webXmls.put(file.getParent().getParent(), Files.readString(file, StandardCharsets.UTF_8));
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
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry zipEntry = enumeration.nextElement();
                String name = zipEntry.getName();
                if (name.startsWith("META-INF/resources/") && name.endsWith(".xhtml")) {
                    Path extracted = LibraryFiles.extract(jar, name);
                    if (extracted == null) {
                        continue;
                    }
                    String text = Files.readString(extracted, StandardCharsets.UTF_8);
                    String webPath = name.substring("META-INF/resources".length());
                    entries.put(extracted.toUri().toString(), new Entry(extracted.toUri().toString(), text,
                            XmlDocument.parse(text), new LineIndex(text), webPath, webPath.substring(1), null));
                } else if (name.startsWith("META-INF/") && name.endsWith(".taglib.xml")) {
                    try (InputStream input = zip.getInputStream(zipEntry)) {
                        addTaglib(TaglibXmlLoader.load(input, jar + "!/" + name), taglibs);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "cannot index " + jar, e);
        }
    }

    /**
     * Places a file below its web root: {@code src/main/webapp} (composites under {@code resources/}) or
     * {@code META-INF/resources} of a module (composites directly below).
     */
    static Entry describe(Path file, String uri, String text, XmlDocument xml) {
        if (file == null) {
            return null;
        }
        String path = file.toString().replace('\\', '/');
        String webPath = null;
        String compositeKey = null;
        Path webRoot = null;
        int metaInf = path.lastIndexOf("/META-INF/resources/");
        int webapp = path.lastIndexOf("/src/main/webapp/");
        if (metaInf >= 0) {
            webPath = path.substring(metaInf + "/META-INF/resources".length());
            compositeKey = webPath.substring(1);
            webRoot = Path.of(path.substring(0, metaInf + "/META-INF/resources".length()));
        } else if (webapp >= 0) {
            webPath = path.substring(webapp + "/src/main/webapp".length());
            if (webPath.startsWith("/resources/")) {
                compositeKey = webPath.substring("/resources/".length());
            }
            webRoot = Path.of(path.substring(0, webapp + "/src/main/webapp".length()));
        }
        return new Entry(uri, text, xml, new LineIndex(text), webPath, compositeKey, webRoot);
    }

    // ---------------------------------------------------------------- snapshot

    /** An immutable view of the index. */
    public static final class Snapshot {
        private final Map<String, Entry> entries;
        private final Map<String, Taglib> taglibs;
        private final Map<Path, String> webXmls;
        private final Map<String, List<String>> byWebPath = new HashMap<>();
        private final Map<String, List<String>> byCompositeKey = new HashMap<>();
        private final Map<String, List<Edge>> forward = new HashMap<>();
        private final Map<String, List<Edge>> reverse = new HashMap<>();
        private final long version;
        private final Map<String, Object> cache = new ConcurrentHashMap<>();

        private Snapshot(Map<String, Entry> entries, Map<String, Taglib> taglibs, Map<Path, String> webXmls, long version) {
            this.entries = entries;
            this.taglibs = taglibs;
            this.webXmls = webXmls;
            this.version = version;
        }

        static Snapshot build(Map<String, Entry> entries, Map<String, Taglib> taglibs, Map<Path, String> webXmls, long version) {
            Snapshot snapshot = new Snapshot(Map.copyOf(entries), Map.copyOf(taglibs), Map.copyOf(webXmls), version);
            snapshot.link();
            return snapshot;
        }

        Snapshot with(Entry entry, long version) {
            Map<String, Entry> copy = new LinkedHashMap<>(entries);
            copy.put(entry.uri(), entry);
            return build(copy, taglibs, webXmls, version);
        }

        Snapshot without(String uri, long version) {
            Map<String, Entry> copy = new LinkedHashMap<>(entries);
            copy.remove(uri);
            return build(copy, taglibs, webXmls, version);
        }

        public long version() {
            return version;
        }

        /** Per-snapshot memo for derived data (client ids), dropped with the snapshot. */
        @SuppressWarnings("unchecked")
        <T> T cached(String key, java.util.function.Supplier<T> compute) {
            return (T) cache.computeIfAbsent(key, k -> compute.get());
        }

        private void link() {
            for (Entry entry : entries.values()) {
                if (entry.webPath() != null) {
                    byWebPath.computeIfAbsent(entry.webPath(), k -> new ArrayList<>()).add(entry.uri());
                }
                if (entry.compositeKey() != null) {
                    byCompositeKey.computeIfAbsent(entry.compositeKey(), k -> new ArrayList<>()).add(entry.uri());
                }
            }
            for (Entry entry : entries.values()) {
                List<Edge> edges = new ArrayList<>();
                for (XmlElement element : entry.xml().allElements()) {
                    for (String target : targets(entry, element)) {
                        edges.add(new Edge(entry.uri(), kindOf(element), element, target));
                    }
                }
                forward.put(entry.uri(), edges);
                for (Edge edge : edges) {
                    reverse.computeIfAbsent(edge.to(), k -> new ArrayList<>()).add(edge);
                }
            }
        }

        private Kind kindOf(XmlElement element) {
            if (Namespaces.FACELETS.equals(Namespaces.canonical(element.namespace()))) {
                return element.localName().equals("include") ? Kind.INCLUDE : Kind.TEMPLATE;
            }
            return Kind.COMPOSITE;
        }

        /** The files an element refers to (several when the same path exists in several modules). */
        List<String> targets(Entry from, XmlElement element) {
            String namespace = element.namespace();
            if (namespace == null) {
                return List.of();
            }
            String canonical = Namespaces.canonical(namespace);
            if (Namespaces.FACELETS.equals(canonical)) {
                String attribute = switch (element.localName()) {
                    case "include" -> "src";
                    case "composition", "decorate" -> "template";
                    default -> null;
                };
                String value = attribute == null ? null : element.attributeValue(attribute);
                if (value == null || value.isBlank() || value.contains("#{") || value.contains("${")) {
                    return List.of();
                }
                return resolvePath(from, value.trim());
            }
            String library = Namespaces.compositeLibrary(canonical);
            if (library == null) {
                Taglib taglib = taglibs.get(canonical);
                if (taglib == null || taglib.compositeLibrary() == null || taglib.declaredTags().contains(element.localName())) {
                    return List.of();
                }
                library = taglib.compositeLibrary();
            }
            return byCompositeKey.getOrDefault(library + "/" + element.localName() + ".xhtml", List.of());
        }

        private List<String> resolvePath(Entry from, String path) {
            String webPath;
            if (path.startsWith("/")) {
                webPath = path;
            } else if (from.webPath() != null) {
                webPath = Path.of(from.webPath()).getParent().resolve(path).normalize().toString().replace('\\', '/');
            } else {
                return List.of();
            }
            List<String> candidates = byWebPath.getOrDefault(webPath, List.of());
            if (candidates.size() <= 1 || from.webRoot() == null) {
                return candidates;
            }
            // the same path in several modules: prefer the including file's own web root
            for (String candidate : candidates) {
                Entry entry = entries.get(candidate);
                if (from.webRoot().equals(entry.webRoot())) {
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
            if (from == null) {
                return path.startsWith("/") ? byWebPath.getOrDefault(path, List.of()) : List.of();
            }
            return resolvePath(from, path);
        }

        public Entry entry(String uri) {
            return entries.get(uri);
        }

        public Collection<Entry> entries() {
            return entries.values();
        }

        public List<Edge> uses(String uri) {
            return forward.getOrDefault(uri, List.of());
        }

        public List<Edge> usages(String uri) {
            return reverse.getOrDefault(uri, List.of());
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
            if (page.webRoot() != null) {
                String webXml = webXmls.get(page.webRoot());
                if (webXml != null) {
                    XmlDocument document = XmlDocument.parse(webXml);
                    for (XmlElement element : document.allElements()) {
                        if (!element.localName().equals("context-param")) {
                            continue;
                        }
                        String name = null;
                        String value = null;
                        for (XmlElement child : element.children()) {
                            if (child.localName().equals("param-name")) {
                                name = child.contentText(webXml).trim();
                            } else if (child.localName().equals("param-value")) {
                                value = child.contentText(webXml).trim();
                            }
                        }
                        if (name != null && name.endsWith(".SEPARATOR_CHAR") && value != null && value.length() == 1) {
                            return value.charAt(0);
                        }
                    }
                }
            }
            return ':';
        }
    }
}
