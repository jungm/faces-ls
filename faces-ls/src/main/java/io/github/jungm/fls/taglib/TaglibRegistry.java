package io.github.jungm.fls.taglib;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.jungm.fls.project.LibraryFiles;

/**
 * Tag libraries for one project: the bundled standard libraries, {@code *.taglib.xml} files on the classpath and
 * in the web application, and composite component libraries from resource directories.
 */
public final class TaglibRegistry {
    private static final Logger LOG = Logger.getLogger(TaglibRegistry.class.getName());

    private static final List<String> BUNDLED = List.of("faces.html", "faces.core", "faces.facelets", "faces.composite",
            "faces.passthrough", "faces", "tags.core", "tags.functions");

    private static final Map<String, TagLibrary> STANDARD = loadBundled();

    private final Map<String, TagLibrary> libraries = new ConcurrentHashMap<>();
    private final Map<String, TagLibrary> composites = new ConcurrentHashMap<>();
    private volatile List<ResourceRoot> resourceRoots = List.of();

    /** A directory or jar containing {@code <library>/<component>.xhtml} composite components. */
    public record ResourceRoot(Path path, String jarPrefix) {
        boolean isJar() {
            return jarPrefix != null;
        }
    }

    private static Map<String, TagLibrary> loadBundled() {
        Map<String, TagLibrary> result = new LinkedHashMap<>();
        for (String name : BUNDLED) {
            String resource = "/io/github/jungm/fls/taglibs/" + name + ".taglib.xml";
            try (InputStream input = TaglibRegistry.class.getResourceAsStream(resource)) {
                if (input == null) {
                    continue;
                }
                TagLibrary library = TaglibXmlLoader.load(input, "Jakarta Faces 4.1 (" + name + ".taglib.xml)",
                        TaglibDescriptor.ofResource(resource));
                if (library != null) {
                    result.put(Namespaces.canonical(library.namespace()), library);
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "cannot load bundled taglib " + resource, e);
            }
        }
        return result;
    }

    /**
     * Reloads project libraries.
     *
     * @param classpath    jars and class directories of the project
     * @param taglibFiles  extra {@code *.taglib.xml} files (WEB-INF, FACELETS_LIBRARIES)
     * @param resourceRoots roots for composite component lookup
     */
    public void refresh(List<Path> classpath, List<Path> taglibFiles, List<ResourceRoot> resourceRoots) {
        Map<String, TagLibrary> loaded = new LinkedHashMap<>();
        List<ResourceRoot> roots = new ArrayList<>(resourceRoots);
        for (Path entry : classpath) {
            if (Files.isRegularFile(entry) && entry.toString().endsWith(".jar")) {
                scanJar(entry, loaded, roots);
            } else if (Files.isDirectory(entry)) {
                Path metaInf = entry.resolve("META-INF");
                if (Files.isDirectory(metaInf)) {
                    try (var files = Files.walk(metaInf, 3)) {
                        files.filter(p -> p.getFileName().toString().endsWith(".taglib.xml"))
                                .forEach(p -> loadFile(p, loaded));
                    } catch (IOException e) {
                        LOG.log(Level.FINE, "cannot scan " + metaInf, e);
                    }
                }
                Path resources = metaInf.resolve("resources");
                if (Files.isDirectory(resources)) {
                    roots.add(new ResourceRoot(resources, null));
                }
            }
        }
        for (Path file : taglibFiles) {
            loadFile(file, loaded);
        }
        libraries.clear();
        libraries.putAll(loaded);
        this.resourceRoots = List.copyOf(roots);
        composites.clear();
    }

    /** Drops cached composite libraries, e.g. after a component file changed. */
    public void invalidateComposites() {
        composites.clear();
    }

    private static void loadFile(Path file, Map<String, TagLibrary> into) {
        try (InputStream input = Files.newInputStream(file)) {
            TagLibrary library = TaglibXmlLoader.load(input, file.toString(), TaglibDescriptor.ofFile(file));
            if (library != null) {
                into.putIfAbsent(Namespaces.canonical(library.namespace()), library);
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "cannot read taglib " + file, e);
        }
    }

    private static void scanJar(Path jar, Map<String, TagLibrary> into, List<ResourceRoot> roots) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            boolean hasResources = false;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("META-INF/") && name.endsWith(".taglib.xml")) {
                    try (InputStream input = zip.getInputStream(entry)) {
                        TagLibrary library = TaglibXmlLoader.load(input, jar.getFileName() + "!/" + name,
                                TaglibDescriptor.ofJarEntry(jar, name));
                        if (library != null) {
                            into.putIfAbsent(Namespaces.canonical(library.namespace()), library);
                        }
                    } catch (IOException | RuntimeException e) {
                        LOG.log(Level.FINE, "cannot read " + name + " in " + jar, e);
                    }
                } else if (name.startsWith("META-INF/resources/") && name.endsWith(".xhtml")) {
                    hasResources = true;
                }
            }
            if (hasResources) {
                roots.add(new ResourceRoot(jar, "META-INF/resources/"));
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "cannot open " + jar, e);
        }
    }

    /** The library for a namespace URI, or null if unknown. */
    public TagLibrary library(String uri) {
        if (uri == null) {
            return null;
        }
        String compositeLibrary = Namespaces.compositeLibrary(uri.trim());
        if (compositeLibrary != null) {
            return composites.computeIfAbsent(compositeLibrary, name -> loadComposite(uri.trim(), name));
        }
        String canonical = Namespaces.canonical(uri);
        TagLibrary standard = STANDARD.get(canonical);
        if (standard != null) {
            return standard;
        }
        TagLibrary library = libraries.get(canonical);
        if (library != null && library.compositeLibraryName() != null) {
            return composites.computeIfAbsent("taglib:" + canonical, key -> withComposites(library));
        }
        return library;
    }

    /** A taglib with {@code <composite-library-name>}: its own tags plus the composite components of that library. */
    private TagLibrary withComposites(TagLibrary library) {
        TagLibrary components = loadComposite(library.namespace(), library.compositeLibraryName());
        return TagLibrary.builder(library.namespace(), library.source())
                .description(library.description())
                .descriptor(library.descriptor())
                .composite(true)
                .compositeLibraryName(library.compositeLibraryName())
                .tags(components.tags())
                .tags(library.tags())
                .functions(library.functions())
                .build();
    }

    public Collection<TagLibrary> projectLibraries() {
        return libraries.values();
    }

    public static Collection<TagLibrary> standardLibraries() {
        return STANDARD.values();
    }

    /** Names of composite libraries found in resource roots. */
    public List<String> compositeLibraryNames() {
        List<String> names = new ArrayList<>();
        for (ResourceRoot root : resourceRoots) {
            if (root.isJar()) {
                try (ZipFile zip = new ZipFile(root.path().toFile())) {
                    zip.stream().map(ZipEntry::getName)
                            .filter(n -> n.startsWith(root.jarPrefix()) && n.endsWith(".xhtml"))
                            .map(n -> n.substring(root.jarPrefix().length()))
                            .filter(n -> n.indexOf('/') > 0)
                            .map(n -> n.substring(0, n.lastIndexOf('/')))
                            .distinct().forEach(n -> {
                                if (!names.contains(n)) {
                                    names.add(n);
                                }
                            });
                } catch (IOException e) {
                    LOG.log(Level.FINE, "cannot open " + root.path(), e);
                }
            } else if (Files.isDirectory(root.path())) {
                try (var dirs = Files.walk(root.path(), 4)) {
                    dirs.filter(Files::isDirectory).filter(d -> !d.equals(root.path())).forEach(dir -> {
                        try (var files = Files.list(dir)) {
                            if (files.anyMatch(f -> f.getFileName().toString().endsWith(".xhtml"))) {
                                String name = root.path().relativize(dir).toString().replace('\\', '/');
                                if (!names.contains(name)) {
                                    names.add(name);
                                }
                            }
                        } catch (IOException ignored) {
                            // skip unreadable directories
                        }
                    });
                } catch (IOException e) {
                    LOG.log(Level.FINE, "cannot scan " + root.path(), e);
                }
            }
        }
        return names;
    }

    private TagLibrary loadComposite(String uri, String libraryName) {
        TagLibrary.Builder builder = TagLibrary.builder(uri, "composite library '" + libraryName + "'").composite(true);
        for (ResourceRoot root : resourceRoots) {
            if (root.isJar()) {
                String prefix = root.jarPrefix() + libraryName + "/";
                try (ZipFile zip = new ZipFile(root.path().toFile())) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.startsWith(prefix) && name.endsWith(".xhtml") && name.indexOf('/', prefix.length()) < 0) {
                            String tagName = name.substring(prefix.length(), name.length() - ".xhtml".length());
                            try (InputStream input = zip.getInputStream(entry)) {
                                String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                                Path extracted = LibraryFiles.extract(root.path(), name);
                                builder.tag(CompositeComponents.describe(tagName,
                                        extracted == null ? null : extracted.toUri().toString(), text));
                            }
                        }
                    }
                } catch (IOException e) {
                    LOG.log(Level.FINE, "cannot read composites in " + root.path(), e);
                }
            } else {
                Path dir = root.path().resolve(libraryName);
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (var files = Files.list(dir)) {
                    for (Path file : (Iterable<Path>) files::iterator) {
                        String fileName = file.getFileName().toString();
                        if (!fileName.endsWith(".xhtml")) {
                            continue;
                        }
                        String tagName = fileName.substring(0, fileName.length() - ".xhtml".length());
                        try {
                            builder.tag(CompositeComponents.describe(tagName, file.toUri().toString(), Files.readString(file)));
                        } catch (IOException e) {
                            LOG.log(Level.FINE, "cannot read " + file, e);
                        }
                    }
                } catch (IOException e) {
                    LOG.log(Level.FINE, "cannot list " + dir, e);
                }
            }
        }
        return builder.build();
    }
}
