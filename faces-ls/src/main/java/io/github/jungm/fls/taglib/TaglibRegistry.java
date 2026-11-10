package io.github.jungm.fls.taglib;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import io.github.jungm.fls.project.LibraryFiles;
import io.github.jungm.fls.project.LibraryJar;
import io.github.jungm.fls.project.WebRoots;

/**
 * Tag libraries for one project: the bundled standard libraries, {@code *.taglib.xml} files on the classpath and
 * in the web application, and composite component libraries from resource directories. What a library jar
 * contributes is read once for all projects ({@link LibraryJar}).
 */
public final class TaglibRegistry {
    private static final Logger LOG = Logger.getLogger(TaglibRegistry.class.getName());

    private static final List<String> BUNDLED = List.of("faces.html", "faces.core", "faces.facelets", "faces.composite",
            "faces.passthrough", "faces", "tags.core", "tags.functions");

    private static final Map<String, TagLibrary> STANDARD = loadBundled();

    /**
     * What the last {@link #refresh} found, replaced as a whole so that a concurrent reader sees either the previous
     * or the new libraries. {@code composites} caches the composite libraries read from {@code resourceRoots}.
     */
    private record State(Map<String, TagLibrary> libraries, List<ResourceRoot> resourceRoots,
                         Map<String, TagLibrary> composites) {
        State withoutComposites() {
            return new State(libraries, resourceRoots, new ConcurrentHashMap<>());
        }
    }

    private volatile State state = new State(Map.of(), List.of(), new ConcurrentHashMap<>());

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
                    result.put(Namespaces.canonical(library.namespace()), withComponentAttributes(library));
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "cannot load bundled taglib " + resource, e);
            }
        }
        return result;
    }

    /**
     * {@code jsf:element} with the attributes every component tag takes, which its taglib leaves out:
     * {@code <span jsf:rendered="...">} is processed as {@code jsf:element}.
     */
    private static TagLibrary withComponentAttributes(TagLibrary library) {
        TagLibrary.TagDef element = library.tags().get("element");
        if (!Namespaces.PASSTHROUGH_ELEMENTS.equals(Namespaces.canonical(library.namespace())) || element == null) {
            return library;
        }
        List<TagLibrary.AttributeDef> attributes = new ArrayList<>(List.of(
                new TagLibrary.AttributeDef("id", "The component identifier for this component. This value must be "
                        + "unique within the closest parent component that is a naming container.", false,
                        "java.lang.String", null, null, -1),
                new TagLibrary.AttributeDef("rendered", "Flag indicating whether or not this component should be "
                        + "rendered (during Render Response Phase), or processed on any subsequent form submit.",
                        false, "boolean", null, "true", -1),
                new TagLibrary.AttributeDef("binding", "The ValueExpression linking this component to a property in a "
                        + "backing bean.", false, "jakarta.faces.component.UIComponent", null, null, -1)));
        attributes.addAll(element.attributes());
        Map<String, TagLibrary.TagDef> tags = new LinkedHashMap<>(library.tags());
        tags.put("element", new TagLibrary.TagDef(element.name(), element.description(), List.copyOf(attributes),
                element.fileUri(), element.componentType()));
        return new TagLibrary(library.namespace(), library.source(), library.composite(), library.attributesComplete(),
                Map.copyOf(tags), library.functions(), library.description(), library.compositeLibraryName(),
                library.descriptor());
    }

    /**
     * Reloads project libraries.
     *
     * @param classpath    jars and class directories of the project
     * @param taglibFiles  extra {@code *.taglib.xml} files (WEB-INF, FACELETS_LIBRARIES)
     * @param resourceRoots roots for composite component lookup
     */
    public synchronized void refresh(List<Path> classpath, List<Path> taglibFiles, List<ResourceRoot> resourceRoots) {
        Map<String, TagLibrary> loaded = new LinkedHashMap<>();
        List<ResourceRoot> roots = new ArrayList<>(resourceRoots);
        for (Path entry : classpath) {
            if (LibraryFiles.isJar(entry)) {
                scanJar(entry, loaded, roots);
            } else if (Files.isDirectory(entry)) {
                Path metaInf = entry.resolve("META-INF");
                if (Files.isDirectory(metaInf)) {
                    try (Stream<Path> files = Files.walk(metaInf, 3)) {
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
        state = new State(Collections.unmodifiableMap(loaded), List.copyOf(roots), new ConcurrentHashMap<>());
    }

    /** Drops cached composite libraries, e.g. after a component file changed. */
    public synchronized void invalidateComposites() {
        state = state.withoutComposites();
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
        LibraryJar library = LibraryJar.of(jar);
        for (TagLibrary taglib : library.taglibs()) {
            into.putIfAbsent(Namespaces.canonical(taglib.namespace()), taglib);
        }
        if (library.hasResources()) {
            roots.add(new ResourceRoot(jar, LibraryJar.META_INF_RESOURCES));
        }
    }

    /** The library for a namespace URI, or null if unknown. */
    public TagLibrary library(String uri) {
        if (uri == null) {
            return null;
        }
        State current = state;
        String compositeLibrary = Namespaces.compositeLibrary(uri.trim());
        if (compositeLibrary != null) {
            return cachedComposite(current, compositeLibrary, () -> loadComposite(current, uri.trim(), compositeLibrary));
        }
        String canonical = Namespaces.canonical(uri);
        TagLibrary standard = STANDARD.get(canonical);
        if (standard != null) {
            return standard;
        }
        TagLibrary library = current.libraries().get(canonical);
        if (library != null && library.compositeLibraryName() != null) {
            return cachedComposite(current, "taglib:" + canonical, () -> withComposites(current, library));
        }
        return library;
    }

    /** A composite library from the cache of {@code current}; it is read outside the map, so readers never wait. */
    private static TagLibrary cachedComposite(State current, String key, Supplier<TagLibrary> load) {
        TagLibrary cached = current.composites().get(key);
        if (cached == null) {
            TagLibrary loaded = load.get();
            cached = current.composites().putIfAbsent(key, loaded);
            if (cached == null) {
                cached = loaded;
            }
        }
        return cached;
    }

    /** A taglib with {@code <composite-library-name>}: its own tags plus the composite components of that library. */
    private static TagLibrary withComposites(State current, TagLibrary library) {
        TagLibrary components = loadComposite(current, library.namespace(), library.compositeLibraryName());
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
        return state.libraries().values();
    }

    public static Collection<TagLibrary> standardLibraries() {
        return STANDARD.values();
    }

    /** Names of composite libraries found in resource roots. */
    public List<String> compositeLibraryNames() {
        List<String> names = new ArrayList<>();
        for (ResourceRoot root : state.resourceRoots()) {
            if (root.isJar()) {
                for (String name : LibraryJar.of(root.path()).compositeLibraryNames()) {
                    if (!names.contains(name)) {
                        names.add(name);
                    }
                }
            } else if (Files.isDirectory(root.path())) {
                try (Stream<Path> dirs = Files.walk(root.path(), 4)) {
                    dirs.filter(Files::isDirectory).filter(d -> !d.equals(root.path())).forEach(dir -> {
                        try (Stream<Path> files = Files.list(dir)) {
                            if (files.anyMatch(f -> f.getFileName().toString().endsWith(".xhtml"))) {
                                String name = WebRoots.webPath(root.path().relativize(dir)).substring(1);
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

    private static TagLibrary loadComposite(State current, String uri, String libraryName) {
        TagLibrary.Builder builder = TagLibrary.builder(uri, "composite library '" + libraryName + "'").composite(true);
        for (ResourceRoot root : current.resourceRoots()) {
            if (root.isJar()) {
                for (TagLibrary.TagDef tag : LibraryJar.of(root.path()).compositeComponents(libraryName)) {
                    builder.tag(tag);
                }
            } else {
                Path dir = root.path().resolve(libraryName);
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(dir)) {
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
