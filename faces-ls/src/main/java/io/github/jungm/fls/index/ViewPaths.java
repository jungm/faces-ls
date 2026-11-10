package io.github.jungm.fls.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import io.github.jungm.fls.index.FaceletsIndex.Entry;
import io.github.jungm.fls.index.FaceletsIndex.Snapshot;
import io.github.jungm.fls.project.LibraryFiles;
import io.github.jungm.fls.project.LibraryJar;
import io.github.jungm.fls.project.ProjectModel;
import io.github.jungm.fls.project.WebRoots;

/**
 * View paths as {@code ui:include src}, {@code template} and similar attributes write them: which file a path
 * denotes and which paths a file can use. Both work with the Facelets index and, while the index is still being
 * built ({@code index == null}), with the project model alone.
 */
public final class ViewPaths {
    private static final Logger LOG = Logger.getLogger(ViewPaths.class.getName());

    /** The view paths of a project without the index, for the configuration load they were listed for. */
    private record Listed(long configuration, List<String> paths) {
    }

    private static final Map<ProjectModel, Listed> LISTED = Collections.synchronizedMap(new WeakHashMap<>());

    private ViewPaths() {
    }

    /**
     * The file that {@code path}, written in the file {@code fromUri}, denotes: relative paths against that file,
     * absolute ones against its web root, and else against the web roots of other modules and
     * {@code META-INF/resources} of the classpath (jar entries are extracted).
     *
     * @param index the current index snapshot, or null
     */
    public static Optional<Path> find(ProjectModel project, Snapshot index, String fromUri, String path) {
        String value = path.trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        Path from = toPath(fromUri);
        Entry entry = index == null ? null : index.entry(fromUri);
        String fromWebPath = null;
        Path webRoot = null;
        if (entry != null && entry.webPath() != null) {
            fromWebPath = entry.webPath();
            webRoot = entry.webRoot();
        }
        if (webRoot == null && from != null) {
            webRoot = project.webRootFor(from);
        }
        if (fromWebPath == null && webRoot != null && from != null) {
            WebRoots.Location location = WebRoots.locate(webRoot, from);
            fromWebPath = location == null ? null : location.webPath();
        }
        String webPath = WebRoots.resolve(fromWebPath, value);
        if (webPath == null) {
            // a relative path in a file outside the web roots
            if (!value.startsWith("/") && from != null && from.getParent() != null) {
                Path local = from.getParent().resolve(value).normalize();
                return Files.isRegularFile(local) ? Optional.of(local) : Optional.empty();
            }
            return Optional.empty();
        }
        if (webPath.endsWith("/")) {
            return Optional.empty();
        }
        if (webRoot != null) {
            Path local = resolve(webRoot, webPath.substring(1));
            if (Files.isRegularFile(local)) {
                return Optional.of(local);
            }
        }
        if (index != null) {
            for (String uri : index.resolveWebPath(webPath, webRoot)) {
                Path file = toPath(uri);
                if (file != null && Files.isRegularFile(file)) {
                    return Optional.of(file);
                }
            }
        }
        return inProject(project, webPath, webRoot);
    }

    /**
     * The file of a web path below one of the project's web roots, preferring {@code preferredWebRoot}, or else below
     * {@code META-INF/resources} of a classpath directory or jar (extracted).
     */
    private static Optional<Path> inProject(ProjectModel project, String webPath, Path preferredWebRoot) {
        String relative = webPath.substring(1);
        List<Path> roots = new ArrayList<>();
        if (preferredWebRoot != null) {
            roots.add(preferredWebRoot);
        }
        roots.addAll(project.webRoots());
        for (Path webRoot : roots) {
            Path file = resolve(webRoot, relative);
            if (Files.isRegularFile(file)) {
                return Optional.of(file);
            }
        }
        String entryName = LibraryJar.META_INF_RESOURCES + relative;
        for (Path entry : project.classpath()) {
            if (LibraryFiles.isJar(entry)) {
                if (LibraryJar.of(entry).contains(entryName)) {
                    return Optional.ofNullable(LibraryFiles.extract(entry, entryName));
                }
            } else {
                Path file = resolve(entry, entryName);
                if (Files.isRegularFile(file)) {
                    return Optional.of(file);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The view paths a file of the project can include: the Facelets files of its own web root, of the project's
     * other web roots, and below {@code META-INF/resources} of modules and jars, in that order.
     *
     * @param index the current index snapshot, or null; without it the project's files are listed, walked once per
     *              configuration load of the project
     */
    public static List<String> includable(ProjectModel project, Snapshot index, String fromUri) {
        if (index == null) {
            return listed(project);
        }
        Path from = toPath(fromUri);
        Path own = from == null ? null : project.webRootFor(from);
        Set<Path> projectRoots = new HashSet<>(project.webRoots());
        List<String> ownPaths = new ArrayList<>();
        List<String> projectPaths = new ArrayList<>();
        List<String> sharedPaths = new ArrayList<>();
        for (Entry entry : index.entries()) {
            Path webRoot = entry.webRoot();
            if (entry.webPath() == null) {
                continue;
            }
            if (webRoot != null && webRoot.equals(own)) {
                ownPaths.add(entry.webPath());
            } else if (webRoot != null && projectRoots.contains(webRoot)) {
                projectPaths.add(entry.webPath());
            } else if (webRoot == null || WebRoots.isMetaInfResources(webRoot)) {
                sharedPaths.add(entry.webPath());
            }
        }
        for (Path classpathEntry : project.classpath()) {
            if (LibraryFiles.isJar(classpathEntry)) {
                sharedPaths.addAll(LibraryJar.of(classpathEntry).viewPaths());
            }
        }
        Set<String> result = new LinkedHashSet<>();
        for (List<String> paths : List.of(ownPaths, projectPaths, sharedPaths)) {
            paths.sort(null);
            result.addAll(paths);
        }
        return List.copyOf(result);
    }

    /**
     * All Facelets files below the project's web roots and below {@code META-INF/resources} of its classpath, as
     * paths relative to their web root.
     */
    private static List<String> listed(ProjectModel project) {
        long configuration = project.configurationVersion();
        Listed cached = LISTED.get(project);
        if (cached != null && cached.configuration() == configuration) {
            return cached.paths();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Path webRoot : project.webRoots()) {
            collectViews(webRoot, result);
        }
        for (Path entry : project.classpath()) {
            if (LibraryFiles.isJar(entry)) {
                result.addAll(LibraryJar.of(entry).viewPaths());
            } else if (Files.isDirectory(entry.resolve("META-INF").resolve("resources"))) {
                collectViews(entry.resolve("META-INF").resolve("resources"), result);
            }
        }
        List<String> paths = List.copyOf(result);
        LISTED.put(project, new Listed(configuration, paths));
        return paths;
    }

    private static void collectViews(Path webRoot, Set<String> result) {
        try (Stream<Path> files = Files.walk(webRoot, 12)) {
            files.filter(p -> p.getFileName().toString().endsWith(".xhtml"))
                    .forEach(p -> result.add(WebRoots.webPath(webRoot.relativize(p))));
        } catch (IOException | UncheckedIOException e) {
            LOG.log(Level.FINE, "cannot scan " + webRoot, e);
        }
    }

    /** {@code dir} resolved with the '/'-separated segments of {@code relative}. */
    private static Path resolve(Path dir, String relative) {
        Path result = dir;
        for (String segment : relative.split("/")) {
            result = result.resolve(segment);
        }
        return result;
    }

    private static Path toPath(String uri) {
        try {
            return Path.of(URI.create(uri));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
