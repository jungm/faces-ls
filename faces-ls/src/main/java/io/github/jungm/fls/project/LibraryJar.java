package io.github.jungm.fls.project;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.jungm.fls.taglib.CompositeComponents;
import io.github.jungm.fls.taglib.TagLibrary;
import io.github.jungm.fls.taglib.TaglibDescriptor;
import io.github.jungm.fls.taglib.TaglibXmlLoader;

/**
 * What faces-ls reads from a library jar: its entry names, the {@code *.taglib.xml} below {@code META-INF}, and the
 * composite components below {@code META-INF/resources}. A jar is read once per version (path, modification time
 * and size) for all projects; reading a newer version of a jar drops the older one.
 */
public final class LibraryJar {
    private static final Logger LOG = Logger.getLogger(LibraryJar.class.getName());
    public static final String META_INF_RESOURCES = "META-INF/resources/";

    private static final Map<Path, LibraryJar> CACHE = new ConcurrentHashMap<>();

    private final Path path;
    private final long modified;
    private final long size;
    private final Set<String> entries;
    private final List<TagLibrary> taglibs;
    private final Map<String, List<TagLibrary.TagDef>> composites = new ConcurrentHashMap<>();

    private LibraryJar(Path path, long modified, long size, Set<String> entries, List<TagLibrary> taglibs) {
        this.path = path;
        this.modified = modified;
        this.size = size;
        this.entries = entries;
        this.taglibs = taglibs;
    }

    /** The jar at {@code path}, read when this version of it was not read before; empty when it cannot be read. */
    public static LibraryJar of(Path path) {
        Path jar = path.toAbsolutePath().normalize();
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(jar, BasicFileAttributes.class);
        } catch (IOException e) {
            CACHE.remove(jar);
            return new LibraryJar(jar, -1, -1, Set.of(), List.of());
        }
        long modified = attributes.lastModifiedTime().toMillis();
        long size = attributes.size();
        return CACHE.compute(jar, (key, cached) -> cached != null && cached.modified == modified && cached.size == size
                ? cached : read(key, modified, size));
    }

    private static LibraryJar read(Path jar, long modified, long size) {
        Set<String> names = new LinkedHashSet<>();
        List<TagLibrary> taglibs = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                names.add(name);
                if (name.startsWith("META-INF/") && name.endsWith(".taglib.xml")) {
                    try (InputStream input = zip.getInputStream(entry)) {
                        TagLibrary library = TaglibXmlLoader.load(input, jar.getFileName() + "!/" + name,
                                TaglibDescriptor.ofJarEntry(jar, name));
                        if (library != null) {
                            taglibs.add(library);
                        }
                    } catch (IOException | RuntimeException e) {
                        LOG.log(Level.FINE, "cannot read " + name + " in " + jar, e);
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "cannot open " + jar, e);
        }
        return new LibraryJar(jar, modified, size, Collections.unmodifiableSet(names), List.copyOf(taglibs));
    }

    public Path path() {
        return path;
    }

    /** Names of all entries. */
    public Set<String> entries() {
        return entries;
    }

    public boolean contains(String entry) {
        return entries.contains(entry);
    }

    /** The tag libraries declared by {@code *.taglib.xml} files below {@code META-INF}. */
    public List<TagLibrary> taglibs() {
        return taglibs;
    }

    /** True when the jar ships Facelets files below {@code META-INF/resources}. */
    public boolean hasResources() {
        return !viewPaths().isEmpty();
    }

    /** The Facelets files below {@code META-INF/resources} as web paths, e.g. {@code /lib/component.xhtml}. */
    public List<String> viewPaths() {
        List<String> result = new ArrayList<>();
        for (String name : entries) {
            if (name.startsWith(META_INF_RESOURCES) && name.endsWith(".xhtml")) {
                result.add(name.substring(META_INF_RESOURCES.length() - 1));
            }
        }
        return result;
    }

    /** Names of the resource libraries with Facelets files, i.e. the directories that hold them. */
    public List<String> compositeLibraryNames() {
        Set<String> names = new LinkedHashSet<>();
        for (String path : viewPaths()) {
            int slash = path.lastIndexOf('/');
            if (slash > 0) {
                names.add(path.substring(1, slash));
            }
        }
        return List.copyOf(names);
    }

    /** The composite components of a resource library, with their files extracted for navigation. */
    public List<TagLibrary.TagDef> compositeComponents(String library) {
        return composites.computeIfAbsent(library, this::readComposites);
    }

    private List<TagLibrary.TagDef> readComposites(String library) {
        String prefix = META_INF_RESOURCES + library + "/";
        List<TagLibrary.TagDef> result = new ArrayList<>();
        if (entries.stream().noneMatch(name -> name.startsWith(prefix))) {
            return result;
        }
        try (ZipFile zip = new ZipFile(path.toFile())) {
            for (String name : entries) {
                if (name.startsWith(prefix) && name.endsWith(".xhtml") && name.indexOf('/', prefix.length()) < 0) {
                    ZipEntry entry = zip.getEntry(name);
                    if (entry == null) {
                        continue;
                    }
                    String tagName = name.substring(prefix.length(), name.length() - ".xhtml".length());
                    try (InputStream input = zip.getInputStream(entry)) {
                        String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                        Path extracted = LibraryFiles.extract(path, name);
                        result.add(CompositeComponents.describe(tagName, extracted == null ? null : extracted.toUri().toString(), text));
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "cannot read composites in " + path, e);
        }
        return List.copyOf(result);
    }
}
