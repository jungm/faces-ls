package io.github.jungm.fls.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Web roots: the directories whose contents a web application serves, and the paths of files below them. The
 * project model and the Facelets index both place files with this class, so a file has the same web root and web
 * path everywhere.
 *
 * <p>A directory is a web root when it is {@code META-INF/resources} (of a module or jar, served from the
 * classpath), {@code src/main/webapp}, or contains a {@code WEB-INF} directory.
 */
public final class WebRoots {
    private static final Logger LOG = Logger.getLogger(WebRoots.class.getName());

    /** Directories that hold build output or tooling files, never sources. */
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("target", "build", "node_modules", "bin", "out");

    /** Web root candidates below a project root, in order of preference. */
    private static final List<List<String>> CONVENTIONAL = List.of(List.of("src", "main", "webapp"), List.of("WebContent"),
            List.of("web"), List.of("webapp"), List.of("src", "main", "resources", "META-INF", "resources"));

    /**
     * Where a file sits in its web application.
     *
     * @param webRoot      the web root containing the file
     * @param webPath      the file's path below the web root, starting with '/', e.g. {@code /WEB-INF/layout.xhtml}
     * @param compositeKey {@code <library>/<component>.xhtml} when the file is a composite component candidate
     *                     ({@code resources/} of a web application, anything below {@code META-INF/resources}),
     *                     else null
     */
    public record Location(Path webRoot, String webPath, String compositeKey) {
    }

    private WebRoots() {
    }

    /** True for directories that hold build output or tooling files ({@code target}, {@code .git}, ...). */
    public static boolean isSkipped(Path dir) {
        Path name = dir.getFileName();
        if (name == null) {
            return false;
        }
        String text = name.toString();
        return SKIPPED_DIRECTORIES.contains(text) || text.startsWith(".");
    }

    /**
     * True when {@code file} lies in a skipped directory ({@code target}, {@code .git}, ...) below the workspace root
     * that contains it, e.g. a resource a build copied to {@code target/classes}. Directories above the workspace
     * root do not count, and a file outside all roots is never skipped.
     */
    public static boolean inSkippedDirectory(List<Path> workspaceRoots, Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        for (Path root : workspaceRoots) {
            Path base = root.toAbsolutePath().normalize();
            if (parent != null && parent.startsWith(base) && insideSkipped(base, parent)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isWebRoot(Path dir) {
        return isMetaInfResources(dir) || endsWith(dir, List.of("src", "main", "webapp"))
                || Files.isDirectory(dir.resolve("WEB-INF"));
    }

    /** True for a {@code META-INF/resources} directory, whose files are served from the classpath. */
    public static boolean isMetaInfResources(Path dir) {
        return endsWith(dir, List.of("META-INF", "resources"));
    }

    /** The innermost web root containing {@code file}, or null. */
    public static Path webRootOf(Path file) {
        if (file == null) {
            return null;
        }
        Path absolute = file.toAbsolutePath().normalize();
        for (Path dir = absolute.getParent(); dir != null; dir = dir.getParent()) {
            if (isWebRoot(dir)) {
                return dir;
            }
        }
        return null;
    }

    /** The web root and web path of a file, or null when it is not below a web root. */
    public static Location locate(Path file) {
        Path webRoot = webRootOf(file);
        return webRoot == null ? null : locate(webRoot, file);
    }

    /** The web path of {@code file} below {@code webRoot}, or null when the file is not below it. */
    public static Location locate(Path webRoot, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        if (!absolute.startsWith(webRoot) || absolute.equals(webRoot)) {
            return null;
        }
        String webPath = webPath(webRoot.relativize(absolute));
        String compositeKey = null;
        if (isMetaInfResources(webRoot)) {
            compositeKey = webPath.substring(1);
        } else if (webPath.startsWith("/resources/")) {
            compositeKey = webPath.substring("/resources/".length());
        }
        return new Location(webRoot, webPath, compositeKey);
    }

    /** A relative path as web path: its name elements joined with '/', with a leading '/'. */
    public static String webPath(Path relative) {
        StringBuilder result = new StringBuilder();
        for (Path name : relative) {
            result.append('/').append(name);
        }
        return result.isEmpty() ? "/" : result.toString();
    }

    /**
     * The web path that {@code path}, written in a file at {@code fromWebPath}, denotes: absolute paths as they are,
     * relative ones against the directory of {@code fromWebPath}. Null when a relative path has no base or leaves
     * the web root.
     */
    public static String resolve(String fromWebPath, String path) {
        if (path.startsWith("/")) {
            return normalize(path);
        }
        if (fromWebPath == null) {
            return null;
        }
        return normalize(fromWebPath.substring(0, fromWebPath.lastIndexOf('/') + 1) + path);
    }

    /** Removes {@code .} and {@code ..} segments of an absolute web path; null when it leaves the web root. */
    public static String normalize(String webPath) {
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : webPath.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    return null;
                }
                segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        String joined = "/" + String.join("/", segments);
        return webPath.endsWith("/") && !segments.isEmpty() ? joined + "/" : joined;
    }

    /**
     * The web roots of a project: the conventional directories that are web roots, or else every directory up to
     * four levels below the root that contains {@code WEB-INF} (outside build output).
     */
    public static List<Path> find(Path projectRoot) {
        Set<Path> roots = new LinkedHashSet<>();
        for (List<String> candidate : CONVENTIONAL) {
            Path dir = projectRoot;
            for (String name : candidate) {
                dir = dir.resolve(name);
            }
            if (Files.isDirectory(dir) && isWebRoot(dir)) {
                roots.add(dir);
            }
        }
        if (roots.isEmpty()) {
            try (Stream<Path> dirs = Files.walk(projectRoot, 4)) {
                dirs.filter(d -> d.getFileName() != null && d.getFileName().toString().equals("WEB-INF") && Files.isDirectory(d))
                        .filter(d -> !insideSkipped(projectRoot, d))
                        .forEach(d -> roots.add(d.getParent()));
            } catch (IOException e) {
                LOG.log(Level.FINE, "cannot scan " + projectRoot, e);
            }
        }
        return List.copyOf(roots);
    }

    private static boolean insideSkipped(Path root, Path dir) {
        for (Path name : root.relativize(dir)) {
            if (isSkipped(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWith(Path dir, List<String> names) {
        int count = dir.getNameCount();
        if (count < names.size()) {
            return false;
        }
        for (int i = 0; i < names.size(); i++) {
            if (!dir.getName(count - names.size() + i).toString().equals(names.get(i))) {
                return false;
            }
        }
        return true;
    }
}
