package io.github.jungm.fls.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import io.github.jungm.fls.Threads;
import io.github.jungm.fls.java.JdtClient;
import io.github.jungm.fls.java.JType;
import io.github.jungm.fls.java.TypeService;
import io.github.jungm.fls.taglib.TaglibRegistry;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * Everything faces-ls knows about one project: its folders and classpath (from jdtls when connected), web roots,
 * faces-config contents, CDI beans and tag libraries.
 *
 * <p>Request threads use a model concurrently. Refreshes and configuration loads run one at a time and publish what
 * they found through volatile fields; the beans have their own lock, so reading them never waits for jdtls.
 */
public final class ProjectModel {
    private static final Logger LOG = Logger.getLogger(ProjectModel.class.getName());

    /** @param library jar or module the bean comes from, or null for the project itself */
    public record Bean(String name, JType type, String scope, String origin, String library,
                       TypeService.SourceLocation location) {
    }

    private final Path root;
    private final JdtClient client;
    private final TypeService types;
    private final TaglibRegistry taglibs = new TaglibRegistry();
    private final Map<Path, CachedProperties> properties = new ConcurrentHashMap<>();
    /** Bundle files by base name, for the current configuration load (replaced by the next one). */
    private volatile Map<String, Optional<Path>> bundleFiles = new ConcurrentHashMap<>();
    private volatile List<Path> sourceFolders = List.of();
    private volatile List<Path> classpath = List.of();
    private volatile List<Path> webRoots = List.of();
    private volatile FacesConfig facesConfig = new FacesConfig();
    private volatile long configurationVersion;
    /** Guards {@link #beans}, {@link #beansLoading} and {@link #beansGeneration} together. */
    private final Object beanLock = new Object();
    private volatile List<Bean> beans;
    private boolean beansLoading;
    private long beansGeneration;
    private volatile boolean discarded;
    private final Runnable onChanged;

    /** Bean searches of all projects; several run at once, so a slow search does not hold up other projects. */
    private static final ScheduledExecutorService BEAN_LOADER =
            Executors.newScheduledThreadPool(3, Threads.daemon("faces-ls-beans"));
    /** Delay before a bean search or a refresh that jdtls did not answer is tried again. */
    private static final long RETRY_SECONDS = 10;

    private volatile String facesPackage;
    private volatile boolean javaModelLoaded;
    private volatile boolean refreshed;
    /** False when jdtls was connected but did not answer the last refresh; it is then tried again. */
    private volatile boolean refreshAnswered;
    private volatile long refreshedAt;

    private record CachedProperties(long modified, PropertiesFile file) {
    }

    public ProjectModel(Path root, JdtClient client) {
        this(root, client, () -> { });
    }

    /**
     * @param onChanged runs when background work changed what the project knows: beans loaded, or a refresh that
     *                  jdtls did not answer the first time
     */
    public ProjectModel(Path root, JdtClient client, Runnable onChanged) {
        this.root = root;
        this.client = client;
        this.onChanged = onChanged;
        this.types = new TypeService(client, root.toString());
    }

    public Path root() {
        return root;
    }

    public TypeService types() {
        return types;
    }

    public TaglibRegistry taglibs() {
        return taglibs;
    }

    public FacesConfig facesConfig() {
        return facesConfig;
    }

    public List<Path> webRoots() {
        return webRoots;
    }

    public List<Path> classpath() {
        return classpath;
    }

    public List<Path> sourceFolders() {
        return sourceFolders;
    }

    /** True once folders and classpath came from jdtls. */
    public boolean javaModelLoaded() {
        return javaModelLoaded;
    }

    /**
     * Refreshes the project on first use, and again when jdtls did not answer the last refresh (at most every few
     * seconds), so that a timeout does not leave the project without its classpath. Concurrent callers wait for the
     * refresh in progress.
     */
    public void ensureRefreshed() {
        if (upToDate()) {
            return;
        }
        synchronized (this) {
            if (upToDate()) {
                return;
            }
            if (!refreshed) {
                refresh();
                return;
            }
            Optional<JsonElement> answer = askProject();
            if (answer.isEmpty()) {
                refreshedAt = System.nanoTime();
                return;
            }
            load(answer);
        }
        onChanged.run();
    }

    private boolean upToDate() {
        return refreshed && (refreshAnswered || System.nanoTime() - refreshedAt < TimeUnit.SECONDS.toNanos(RETRY_SECONDS));
    }

    public synchronized void refresh() {
        load(askProject());
    }

    /** jdtls' description of the project: JSON null when not connected, empty when jdtls gave no answer. */
    private Optional<JsonElement> askProject() {
        if (client == null || !client.connected()) {
            return Optional.of(JsonNull.INSTANCE);
        }
        JsonObject params = new JsonObject();
        params.addProperty("path", root.toString());
        return client.query("project", params, 15000);
    }

    /** Takes folders and classpath from jdtls' answer, or from the conventional layout, and reloads everything. */
    private void load(Optional<JsonElement> answer) {
        List<Path> sources = new ArrayList<>();
        List<Path> jars = new ArrayList<>();
        boolean loaded = false;
        boolean answered = answer.isPresent();
        if (answered && answer.get().isJsonObject()) {
            JsonObject project = answer.get().getAsJsonObject();
            project.getAsJsonArray("sourceFolders").forEach(e -> sources.add(Path.of(e.getAsString())));
            project.getAsJsonArray("classpath").forEach(e -> jars.add(Path.of(e.getAsString())));
            loaded = true;
        }
        if (sources.isEmpty()) {
            for (String candidate : List.of("src/main/java", "src/main/resources", "src")) {
                Path dir = root.resolve(candidate);
                if (Files.isDirectory(dir)) {
                    sources.add(dir);
                }
            }
        }
        sourceFolders = List.copyOf(sources);
        classpath = List.copyOf(jars);
        webRoots = findWebRoots();
        javaModelLoaded = loaded;
        types.invalidate();
        facesPackage = null;
        reloadConfiguration();
        refreshAnswered = answered;
        refreshedAt = System.nanoTime();
        refreshed = true;
        LOG.info("project " + root + ": " + sourceFolders.size() + " source folders, " + classpath.size()
                + " classpath entries, web roots " + webRoots
                + (loaded ? "" : answered ? " (without jdtls)" : " (jdtls did not answer)"));
    }

    /** Sets the classpath directly, for callers that know it without jdtls, and reloads the configuration. */
    public synchronized void useClasspath(List<Path> entries) {
        classpath = List.copyOf(entries);
        reloadConfiguration();
    }

    /** Re-reads faces-config.xml, web.xml and tag libraries. */
    public synchronized void reloadConfiguration() {
        FacesConfig config = new FacesConfig();
        List<Path> taglibFiles = new ArrayList<>();
        List<TaglibRegistry.ResourceRoot> resourceRoots = new ArrayList<>();
        for (Path webRoot : webRoots) {
            Path webInf = webRoot.resolve("WEB-INF");
            Path facesConfigFile = webInf.resolve("faces-config.xml");
            if (Files.isRegularFile(facesConfigFile)) {
                config.read(facesConfigFile);
            }
            if (Files.isDirectory(webInf)) {
                try (Stream<Path> files = Files.walk(webInf, 3)) {
                    files.filter(p -> p.getFileName().toString().endsWith(".taglib.xml")).forEach(taglibFiles::add);
                } catch (IOException e) {
                    LOG.log(Level.FINE, "cannot scan " + webInf, e);
                }
            }
            taglibFiles.addAll(WebXml.read(webRoot).faceletsLibraries(webRoot));
            Path resources = webRoot.resolve("resources");
            if (Files.isDirectory(resources)) {
                resourceRoots.add(new TaglibRegistry.ResourceRoot(resources, null));
            }
        }
        for (Path source : sourceFolders) {
            Path metaInf = source.resolve("META-INF");
            if (!Files.isDirectory(metaInf)) {
                continue;
            }
            try (Stream<Path> files = Files.list(metaInf)) {
                files.filter(p -> p.getFileName().toString().endsWith("faces-config.xml")).forEach(config::read);
            } catch (IOException e) {
                LOG.log(Level.FINE, "cannot list " + metaInf, e);
            }
            try (Stream<Path> files = Files.walk(metaInf, 3)) {
                files.filter(p -> p.getFileName().toString().endsWith(".taglib.xml")).forEach(taglibFiles::add);
            } catch (IOException e) {
                LOG.log(Level.FINE, "cannot scan " + metaInf, e);
            }
            Path resources = metaInf.resolve("resources");
            if (Files.isDirectory(resources)) {
                resourceRoots.add(new TaglibRegistry.ResourceRoot(resources, null));
            }
        }
        // faces-config.xml shipped in modules and jars: resource bundles and managed beans of libraries
        for (Path entry : classpath) {
            if (LibraryFiles.isJar(entry)) {
                for (String name : LibraryJar.of(entry).entries()) {
                    if (name.startsWith("META-INF/") && name.indexOf('/', "META-INF/".length()) < 0
                            && name.endsWith("faces-config.xml")) {
                        Path extracted = LibraryFiles.extract(entry, name);
                        if (extracted != null) {
                            config.read(extracted);
                        }
                    }
                }
            } else if (Files.isDirectory(entry.resolve("META-INF"))) {
                try (Stream<Path> files = Files.list(entry.resolve("META-INF"))) {
                    files.filter(p -> p.getFileName().toString().endsWith("faces-config.xml")).forEach(config::read);
                } catch (IOException e) {
                    LOG.log(Level.FINE, "cannot list " + entry, e);
                }
            }
        }
        facesConfig = config;
        taglibs.refresh(classpath, taglibFiles, resourceRoots);
        bundleFiles = new ConcurrentHashMap<>();
        configurationVersion++;
        invalidateBeans();
    }

    /** Increments with every configuration load, for caches of what the configuration determines. */
    public long configurationVersion() {
        return configurationVersion;
    }

    /** The web roots of the project ({@link WebRoots#find}), or the project root when it has none. */
    private List<Path> findWebRoots() {
        List<Path> found = WebRoots.find(root);
        return found.isEmpty() ? List.of(root) : found;
    }

    /** The web root containing the file ({@link WebRoots#webRootOf}), or else the first web root. */
    public Path webRootFor(Path file) {
        Path located = WebRoots.webRootOf(file);
        if (located != null) {
            return located;
        }
        for (Path webRoot : webRoots) {
            if (file.startsWith(webRoot)) {
                return webRoot;
            }
        }
        return webRoots.isEmpty() ? root : webRoots.get(0);
    }

    /** {@code jakarta} or {@code javax}, depending on which Faces API is on the classpath. */
    public String facesPackage() {
        String cached = facesPackage;
        if (cached != null) {
            return cached;
        }
        String detected = "jakarta";
        if (types.available()) {
            if (types.info("jakarta.faces.context.FacesContext") == null && types.info("javax.faces.context.FacesContext") != null) {
                detected = "javax";
            }
            facesPackage = detected;
        } else {
            for (Path entry : classpath) {
                String name = entry.getFileName().toString();
                if (name.startsWith("javax.faces") || name.startsWith("jsf-api") || name.startsWith("myfaces-api-2")) {
                    detected = "javax";
                }
            }
        }
        return detected;
    }

    /**
     * Named beans from jdtls (CDI {@code @Named}, {@code @ManagedBean}) and faces-config managed beans. The jdtls
     * search runs in the background, since it can take seconds while jdtls indexes; until it completes this returns
     * the faces-config beans only and {@link #beansLoaded()} is false. {@code onChanged} runs when it completes.
     */
    public List<Bean> beans() {
        List<Bean> cached = beans;
        if (cached != null) {
            return cached;
        }
        if (client == null || !client.connected()) {
            return withManagedBeans(new ArrayList<>());
        }
        synchronized (beanLock) {
            startLoadingBeans(0);
        }
        return withManagedBeans(new ArrayList<>());
    }

    /** False while the bean search in jdtls is still running; unknown names must not be reported meanwhile. */
    public boolean beansLoaded() {
        return beans != null || client == null || !client.connected();
    }

    /** Starts a bean search unless one runs or the beans are known; the caller holds {@link #beanLock}. */
    private void startLoadingBeans(long delaySeconds) {
        if (beansLoading || beans != null || discarded) {
            return;
        }
        beansLoading = true;
        long generation = beansGeneration;
        BEAN_LOADER.schedule(() -> loadBeans(generation), delaySeconds, TimeUnit.SECONDS);
    }

    private void invalidateBeans() {
        synchronized (beanLock) {
            beans = null;
            beansGeneration++;
        }
    }

    /**
     * Searches the beans and stores them unless the project changed meanwhile. A search whose answer is outdated
     * starts again at once, one that got no answer a little later, so that the beans are eventually loaded and
     * {@code onChanged} runs.
     */
    private void loadBeans(long generation) {
        Optional<JsonElement> response = Optional.empty();
        List<Bean> result = new ArrayList<>();
        try {
            JsonObject params = new JsonObject();
            params.addProperty("path", root.toString());
            response = client.query("beans", params, 60000);
            if (response.isPresent() && response.get().isJsonArray()) {
                for (JsonElement element : response.get().getAsJsonArray()) {
                    JsonObject bean = element.getAsJsonObject();
                    result.add(new Bean(bean.get("name").getAsString(), JType.parse(bean.get("type").getAsString()),
                            bean.has("scope") ? bean.get("scope").getAsString() : null,
                            "@" + bean.get("kind").getAsString(),
                            bean.has("library") ? bean.get("library").getAsString() : null,
                            TypeService.toLocation(bean.get("location"))));
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "cannot read the beans of " + root, e);
            response = Optional.empty();
        }
        synchronized (beanLock) {
            beansLoading = false;
            if (discarded || !client.connected()) {
                // a new connection replaces the projects
                return;
            }
            if (generation != beansGeneration) {
                startLoadingBeans(0);
                return;
            }
            if (response.isEmpty()) {
                LOG.fine(() -> "no answer to the bean search of " + root + ", trying again");
                startLoadingBeans(RETRY_SECONDS);
                return;
            }
            beans = List.copyOf(withManagedBeans(result));
        }
        onChanged.run();
    }

    private List<Bean> withManagedBeans(List<Bean> result) {
        for (FacesConfig.ManagedBean managed : facesConfig.managedBeans()) {
            if (result.stream().noneMatch(b -> b.name().equals(managed.name()))) {
                result.add(new Bean(managed.name(), JType.parse(managed.className()), managed.scope(), "faces-config.xml",
                        null, null));
            }
        }
        return result;
    }

    public void invalidateJava() {
        types.invalidate();
        invalidateBeans();
        facesPackage = null;
    }

    /** The project is replaced by a new model; it starts no more background work. */
    public void discard() {
        discarded = true;
        invalidateJava();
    }

    /**
     * The default-locale properties file of a resource bundle base name, or null. Bundles are looked up in source
     * folders, then in class directories and jars of the classpath (extracted for navigation).
     */
    public PropertiesFile bundle(String baseName) {
        Map<String, Optional<Path>> files = bundleFiles;
        Optional<Path> location = files.get(baseName);
        if (location == null) {
            location = findBundle(baseName);
            files.putIfAbsent(baseName, location);
        }
        if (location.isEmpty()) {
            return null;
        }
        Path file = location.get();
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            CachedProperties cached = properties.get(file);
            if (cached == null || cached.modified() != modified) {
                cached = new CachedProperties(modified, PropertiesFile.read(file));
                properties.put(file, cached);
            }
            return cached.file();
        } catch (IOException e) {
            files.remove(baseName);
            return null;
        }
    }

    private Optional<Path> findBundle(String baseName) {
        String relative = baseName.replace('.', '/') + ".properties";
        List<Path> folders = new ArrayList<>(sourceFolders);
        for (Path entry : classpath) {
            if (Files.isDirectory(entry)) {
                folders.add(entry);
            }
        }
        for (Path folder : folders) {
            Path file = folder.resolve(relative);
            if (Files.isRegularFile(file)) {
                return Optional.of(file);
            }
        }
        for (Path entry : classpath) {
            if (LibraryFiles.isJar(entry) && LibraryJar.of(entry).contains(relative)) {
                return Optional.ofNullable(LibraryFiles.extract(entry, relative));
            }
        }
        return Optional.empty();
    }
}
