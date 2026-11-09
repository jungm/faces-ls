package io.github.jungm.fls.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import io.github.jungm.fls.java.JdtClient;
import io.github.jungm.fls.java.JType;
import io.github.jungm.fls.java.TypeService;
import io.github.jungm.fls.taglib.TaglibRegistry;
import io.github.jungm.fls.xml.XmlDocument;
import io.github.jungm.fls.xml.XmlElement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Everything faces-ls knows about one project: its folders and classpath (from jdtls when connected), web roots,
 * faces-config contents, CDI beans and tag libraries.
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
    private final Map<String, Optional<Path>> bundleFiles = new ConcurrentHashMap<>();
    private volatile List<Path> sourceFolders = List.of();
    private volatile List<Path> classpath = List.of();
    private volatile List<Path> webRoots = List.of();
    private volatile FacesConfig facesConfig = new FacesConfig();
    private volatile List<Bean> beans;
    private volatile boolean beansLoading;
    private volatile long beansGeneration;
    private final Runnable onBeansLoaded;

    private static final java.util.concurrent.ExecutorService BEAN_LOADER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "faces-ls-beans");
        thread.setDaemon(true);
        return thread;
    });
    private volatile String facesPackage;
    private volatile boolean javaModelLoaded;

    private record CachedProperties(long modified, PropertiesFile file) {
    }

    public ProjectModel(Path root, JdtClient client) {
        this(root, client, () -> { });
    }

    public ProjectModel(Path root, JdtClient client, Runnable onBeansLoaded) {
        this.root = root;
        this.client = client;
        this.onBeansLoaded = onBeansLoaded;
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

    public synchronized void refresh() {
        List<Path> sources = new ArrayList<>();
        List<Path> jars = new ArrayList<>();
        boolean loaded = false;
        if (client != null && client.connected()) {
            JsonObject params = new JsonObject();
            params.addProperty("path", root.toString());
            JsonElement response = client.request("project", params, 15000);
            if (response.isJsonObject()) {
                JsonObject project = response.getAsJsonObject();
                project.getAsJsonArray("sourceFolders").forEach(e -> sources.add(Path.of(e.getAsString())));
                project.getAsJsonArray("classpath").forEach(e -> jars.add(Path.of(e.getAsString())));
                loaded = true;
            }
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
        beans = null;
        beansGeneration++;
        facesPackage = null;
        reloadConfiguration();
        LOG.info("project " + root + ": " + sourceFolders.size() + " source folders, " + classpath.size()
                + " classpath entries, web roots " + webRoots + (loaded ? "" : " (without jdtls)"));
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
            taglibFiles.addAll(contextParamLibraries(webRoot));
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
                for (String name : LibraryFiles.entries(entry)) {
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
        bundleFiles.clear();
        beans = null;
        beansGeneration++;
    }

    private List<Path> contextParamLibraries(Path webRoot) {
        Path webXml = webRoot.resolve("WEB-INF/web.xml");
        List<Path> result = new ArrayList<>();
        if (!Files.isRegularFile(webXml)) {
            return result;
        }
        try {
            String text = Files.readString(webXml);
            XmlDocument document = XmlDocument.parse(text);
            for (XmlElement element : document.allElements()) {
                if (!element.localName().equals("context-param")) {
                    continue;
                }
                String name = null;
                String value = null;
                for (XmlElement child : element.children()) {
                    if (child.localName().equals("param-name")) {
                        name = child.contentText(text).trim();
                    } else if (child.localName().equals("param-value")) {
                        value = child.contentText(text).trim();
                    }
                }
                if (value != null && name != null && name.endsWith(".FACELETS_LIBRARIES")) {
                    for (String library : value.split(";")) {
                        String path = library.trim();
                        if (!path.isEmpty()) {
                            result.add(webRoot.resolve(path.startsWith("/") ? path.substring(1) : path));
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "cannot read " + webXml, e);
        }
        return result;
    }

    private List<Path> findWebRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        for (String candidate : List.of("src/main/webapp", "WebContent", "web", "webapp", "src/main/resources/META-INF/resources")) {
            Path dir = root.resolve(candidate);
            if (Files.isDirectory(dir)) {
                roots.add(dir);
            }
        }
        if (roots.isEmpty()) {
            try (Stream<Path> dirs = Files.walk(root, 4)) {
                dirs.filter(d -> d.getFileName() != null && d.getFileName().toString().equals("WEB-INF") && Files.isDirectory(d))
                        .filter(d -> !d.toString().contains("/target/") && !d.toString().contains("/build/"))
                        .forEach(d -> roots.add(d.getParent()));
            } catch (IOException e) {
                LOG.log(Level.FINE, "cannot scan " + root, e);
            }
        }
        if (roots.isEmpty()) {
            roots.add(root);
        }
        return List.copyOf(roots);
    }

    /** The web root containing the file, or the first web root. */
    public Path webRootFor(Path file) {
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
     * the faces-config beans only and {@link #beansLoaded()} is false. {@code onBeansLoaded} runs when it completes.
     */
    public List<Bean> beans() {
        List<Bean> cached = beans;
        if (cached != null) {
            return cached;
        }
        if (client == null || !client.connected()) {
            return withManagedBeans(new ArrayList<>());
        }
        if (!beansLoading) {
            beansLoading = true;
            long generation = beansGeneration;
            BEAN_LOADER.execute(() -> loadBeans(generation));
        }
        return withManagedBeans(new ArrayList<>());
    }

    /** False while the bean search in jdtls is still running; unknown names must not be reported meanwhile. */
    public boolean beansLoaded() {
        return beans != null || client == null || !client.connected();
    }

    private void loadBeans(long generation) {
        List<Bean> result = new ArrayList<>();
        JsonObject params = new JsonObject();
        params.addProperty("path", root.toString());
        JsonElement response = client.request("beans", params, 60000);
        if (response.isJsonArray()) {
            for (JsonElement element : response.getAsJsonArray()) {
                JsonObject bean = element.getAsJsonObject();
                result.add(new Bean(bean.get("name").getAsString(), JType.parse(bean.get("type").getAsString()),
                        bean.has("scope") ? bean.get("scope").getAsString() : null,
                        "@" + bean.get("kind").getAsString(),
                        bean.has("library") ? bean.get("library").getAsString() : null,
                        TypeService.toLocation(bean.get("location"))));
            }
        }
        synchronized (this) {
            beansLoading = false;
            if (generation != beansGeneration || !client.connected() || !response.isJsonArray()) {
                return;
            }
            beans = List.copyOf(withManagedBeans(result));
        }
        onBeansLoaded.run();
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

    public synchronized void invalidateJava() {
        types.invalidate();
        beans = null;
        beansGeneration++;
        facesPackage = null;
    }

    /**
     * The default-locale properties file of a resource bundle base name, or null. Bundles are looked up in source
     * folders, then in class directories and jars of the classpath (extracted for navigation).
     */
    public PropertiesFile bundle(String baseName) {
        Optional<Path> location = bundleFiles.computeIfAbsent(baseName, this::findBundle);
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
            bundleFiles.remove(baseName);
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
            if (LibraryFiles.isJar(entry) && LibraryFiles.contains(entry, relative)) {
                return Optional.ofNullable(LibraryFiles.extract(entry, relative));
            }
        }
        return Optional.empty();
    }

    /** All Facelets files below the web roots, as paths relative to their web root (starting with '/'). */
    public List<String> viewPaths() {
        List<String> result = new ArrayList<>();
        for (Path webRoot : webRoots) {
            try (Stream<Path> files = Files.walk(webRoot, 12)) {
                files.filter(p -> p.getFileName().toString().endsWith(".xhtml"))
                        .forEach(p -> result.add("/" + webRoot.relativize(p).toString().replace('\\', '/')));
            } catch (IOException e) {
                LOG.log(Level.FINE, "cannot scan " + webRoot, e);
            }
        }
        return result;
    }
}
