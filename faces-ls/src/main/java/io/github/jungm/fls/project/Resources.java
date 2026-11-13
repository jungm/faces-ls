package io.github.jungm.fls.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Faces resources ({@code ResourceHandler}): the files a library and resource name denote, following the packaging
 * of the specification (Jakarta Faces 4.1, 2.6.1): {@code resources/} of web roots (or the
 * {@code WEBAPP_RESOURCES_DIRECTORY} context parameter), {@code META-INF/resources/} of source folders, classpath
 * directories and jars, and the same below resource library contracts ({@code contracts/<contract>/},
 * {@code WEBAPP_CONTRACTS_DIRECTORY}, {@code META-INF/contracts/<contract>/}).
 *
 * <p>A resource identifier is {@code [localePrefix/][libraryName/][libraryVersion/]resourceName[/resourceVersion]};
 * the highest library and resource versions win. The locale prefix depends on the request, so resources are first
 * looked up without one and then below every directory named like a locale.
 */
public final class Resources {
    private static final String RESOURCES = "resources";
    private static final String CONTRACTS = "contracts";
    private static final String META_INF_RESOURCES = "META-INF/resources/";
    private static final String META_INF_CONTRACTS = "META-INF/contracts/";
    private static final String CONTRACT_MARKER = "jakarta.faces.contract.xml";
    private static final String LEGACY_CONTRACT_MARKER = "javax.faces.contract.xml";

    private static final Pattern VERSION = Pattern.compile("[0-9]+(_[0-9]+)*");
    private static final Pattern RESOURCE_VERSION = Pattern.compile("([0-9]+(?:_[0-9]+)*)(\\..*)?");
    private static final Pattern LOCALE = Pattern.compile("[A-Za-z]{2}(_[A-Za-z]{2}(_[A-Za-z]+)*)?");

    private Resources() {
    }

    /** A resource reference, {@code library} null for resources outside a library. */
    public record Reference(String library, String name) {
        /**
         * The reference an EL key of {@code #{resource['...']}} denotes: {@code library:name} or {@code name};
         * null for more than one colon or empty parts.
         */
        public static Reference parse(String key) {
            if (key == null) {
                return null;
            }
            int colon = key.indexOf(':');
            if (colon < 0) {
                return key.isBlank() ? null : new Reference(null, key.trim());
            }
            if (key.indexOf(':', colon + 1) >= 0) {
                return null;
            }
            String library = key.substring(0, colon).trim();
            String name = key.substring(colon + 1).trim();
            return library.isEmpty() || name.isEmpty() ? null : new Reference(library, name);
        }
    }

    /**
     * The files a resource resolves to: those in the resource directories first, then those of resource library
     * contracts (which take precedence at runtime when the view uses the contract). Jar entries are extracted.
     */
    public static List<Path> find(ProjectModel project, String library, String name) {
        if (!validName(name) || library != null && !validSegment(library)) {
            return List.of();
        }
        Set<Path> result = new LinkedHashSet<>();
        for (Tree root : resourceRoots(project)) {
            addIfFound(result, resolve(root, library, name));
        }
        for (Tree root : contractRoots(project)) {
            for (String contract : root.children("")) {
                if (root.isDirectory(contract)) {
                    addIfFound(result, resolve(root.sub(contract), library, name));
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * The files {@code this:name} resolves to in {@code file}: {@code this} is the library of a file in a resource
     * library (a composite component) or the contract of a file in a resource library contract (a contract's
     * template). Empty when the file is in neither.
     */
    public static List<Path> findFromThis(ProjectModel project, Path file, String name) {
        if (file == null || !validName(name)) {
            return List.of();
        }
        Path absolute = file.toAbsolutePath().normalize();
        Set<Path> result = new LinkedHashSet<>();
        String library = enclosingDirectory(resourceRoots(project), absolute);
        if (library != null) {
            result.addAll(find(project, library, name));
        }
        String contract = enclosingDirectory(contractRoots(project), absolute);
        if (contract != null) {
            for (Tree root : contractRoots(project)) {
                addIfFound(result, resolve(root, contract, name));
            }
        }
        return List.copyOf(result);
    }

    /**
     * The resource library contract of that name in every contracts directory: its templates (the Facelets files at
     * its top level), else its marker file ({@code jakarta.faces.contract.xml}), else its directory. Editors open
     * files more readily than directories.
     */
    public static List<Path> contract(ProjectModel project, String contract) {
        if (!validSegment(contract)) {
            return List.of();
        }
        Set<Path> result = new LinkedHashSet<>();
        for (Tree root : contractRoots(project)) {
            if (!root.isDirectory(contract)) {
                continue;
            }
            List<String> files = root.children(contract).stream().filter(name -> root.isFile(contract + "/" + name)).toList();
            List<String> templates = files.stream().filter(name -> name.endsWith(".xhtml")).toList();
            List<String> markers = files.stream()
                    .filter(name -> name.equals(CONTRACT_MARKER) || name.equals(LEGACY_CONTRACT_MARKER)).toList();
            List<String> chosen = !templates.isEmpty() ? templates : markers;
            for (String name : chosen) {
                addIfFound(result, root.file(contract + "/" + name));
            }
            if (chosen.isEmpty()) {
                addIfFound(result, root.directory(contract));
            }
        }
        return List.copyOf(result);
    }

    // ---------------------------------------------------------------- roots

    /** Where resources are packaged, in the order the runtime looks: web roots before the classpath. */
    static List<Tree> resourceRoots(ProjectModel project) {
        Set<Path> directories = new LinkedHashSet<>();
        for (Path webRoot : project.webRoots()) {
            directories.add(WebRoots.isMetaInfResources(webRoot) ? webRoot
                    : webRoot.resolve(webappDirectory(webRoot, "WEBAPP_RESOURCES_DIRECTORY", RESOURCES)));
        }
        for (Path source : project.sourceFolders()) {
            directories.add(source.resolve(META_INF_RESOURCES));
        }
        return roots(project, directories, META_INF_RESOURCES);
    }

    /** Where resource library contracts are packaged; each directory below one of them is a contract. */
    static List<Tree> contractRoots(ProjectModel project) {
        Set<Path> directories = new LinkedHashSet<>();
        for (Path webRoot : project.webRoots()) {
            directories.add(WebRoots.isMetaInfResources(webRoot) ? webRoot.resolveSibling(CONTRACTS)
                    : webRoot.resolve(webappDirectory(webRoot, "WEBAPP_CONTRACTS_DIRECTORY", CONTRACTS)));
        }
        for (Path source : project.sourceFolders()) {
            directories.add(source.resolve(META_INF_CONTRACTS));
        }
        return roots(project, directories, META_INF_CONTRACTS);
    }

    private static List<Tree> roots(ProjectModel project, Set<Path> directories, String classpathPrefix) {
        List<Tree> roots = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (Path directory : directories) {
            Path normalized = directory.toAbsolutePath().normalize();
            if (seen.add(normalized) && Files.isDirectory(normalized)) {
                roots.add(new DirectoryTree(normalized));
            }
        }
        for (Path entry : project.classpath()) {
            if (LibraryFiles.isJar(entry)) {
                LibraryJar jar = LibraryJar.of(entry);
                if (jar.entries().stream().anyMatch(name -> name.startsWith(classpathPrefix))) {
                    roots.add(new JarTree(jar, classpathPrefix));
                }
            } else {
                Path normalized = entry.resolve(classpathPrefix).toAbsolutePath().normalize();
                if (seen.add(normalized) && Files.isDirectory(normalized)) {
                    roots.add(new DirectoryTree(normalized));
                }
            }
        }
        return roots;
    }

    /**
     * The directory a {@code WEBAPP_*_DIRECTORY} context parameter of the web root's web.xml names, relative to the
     * web root; the default when it is missing or invalid (absolute, or leaving the web root).
     */
    private static String webappDirectory(Path webRoot, String param, String fallback) {
        String value = WebXml.read(webRoot).facesParam(param);
        if (value == null || value.isBlank() || value.startsWith("/") || value.contains("\\")) {
            return fallback;
        }
        for (String segment : value.split("/")) {
            if (segment.equals("..")) {
                return fallback;
            }
        }
        return value.trim();
    }

    /** The first directory below one of the roots that contains {@code file}, e.g. the library of a composite. */
    private static String enclosingDirectory(List<Tree> roots, Path file) {
        for (Tree root : roots) {
            if (root instanceof DirectoryTree directory && file.startsWith(directory.root())) {
                Path relative = directory.root().relativize(file);
                if (relative.getNameCount() >= 2) {
                    return relative.getName(0).toString();
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- resolution

    /** The file of a resource below one root: without a locale prefix, else below a locale directory. */
    private static Path resolve(Tree root, String library, String name) {
        Path found = resolveIn(root, "", library, name);
        if (found != null) {
            return found;
        }
        for (String child : root.children("")) {
            if (LOCALE.matcher(child).matches() && !child.equals(library) && root.isDirectory(child)) {
                found = resolveIn(root, child + "/", library, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Path resolveIn(Tree root, String base, String library, String name) {
        String prefix = base;
        if (library != null) {
            String libraryDir = base + library;
            if (!root.isDirectory(libraryDir)) {
                return null;
            }
            String version = highest(root.children(libraryDir).stream()
                    .filter(child -> VERSION.matcher(child).matches() && root.isDirectory(libraryDir + "/" + child)));
            prefix = libraryDir + "/" + (version == null ? "" : version + "/");
        }
        String resource = prefix + name;
        if (root.isFile(resource)) {
            return root.file(resource);
        }
        if (root.isDirectory(resource)) {
            String version = highest(root.children(resource).stream()
                    .filter(child -> RESOURCE_VERSION.matcher(child).matches() && root.isFile(resource + "/" + child)));
            if (version != null) {
                return root.file(resource + "/" + version);
            }
        }
        return null;
    }

    /** The highest of the version names ({@code 1_2}, {@code 1_2_3.js}), compared by their numbers. */
    private static String highest(Stream<String> versions) {
        return versions.max(Comparator.comparing(Resources::numbers, Resources::compareVersions)
                .thenComparing(Comparator.naturalOrder())).orElse(null);
    }

    private static long[] numbers(String version) {
        int dot = version.indexOf('.');
        String[] parts = (dot < 0 ? version : version.substring(0, dot)).split("_");
        long[] result = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Long.parseLong(parts[i]);
            } catch (NumberFormatException e) {
                result[i] = Long.MAX_VALUE;
            }
        }
        return result;
    }

    private static int compareVersions(long[] a, long[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = i < a.length ? a[i] : 0;
            long y = i < b.length ? b[i] : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return 0;
    }

    /** A resource name: relative, '/' separated, without empty, '.' or '..' segments. */
    private static boolean validName(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.contains("\\") || name.contains(":")) {
            return false;
        }
        for (String segment : name.split("/", -1)) {
            if (!validSegment(segment)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validSegment(String segment) {
        return segment != null && !segment.isBlank() && !segment.equals(".") && !segment.equals("..")
                && segment.indexOf('/') < 0 && segment.indexOf('\\') < 0 && segment.indexOf(':') < 0;
    }

    private static void addIfFound(Set<Path> result, Path path) {
        if (path != null) {
            result.add(path);
        }
    }

    // ---------------------------------------------------------------- trees

    /** A directory of the file system or of a jar, addressed by '/' separated relative paths. */
    sealed interface Tree permits DirectoryTree, JarTree {
        boolean isFile(String path);

        boolean isDirectory(String path);

        /** Names of the entries directly below a directory, sorted. */
        List<String> children(String path);

        /** The file to open for a file entry (extracted from a jar), or null. */
        Path file(String path);

        /** The directory to open for a directory entry, or null when it has none on disk. */
        Path directory(String path);

        Tree sub(String path);
    }

    record DirectoryTree(Path root) implements Tree {
        private Path at(String path) {
            Path resolved = path.isEmpty() ? root : root.resolve(path).normalize();
            return resolved.startsWith(root) ? resolved : null;
        }

        @Override
        public boolean isFile(String path) {
            Path file = at(path);
            return file != null && Files.isRegularFile(file);
        }

        @Override
        public boolean isDirectory(String path) {
            Path dir = at(path);
            return dir != null && Files.isDirectory(dir);
        }

        @Override
        public List<String> children(String path) {
            Path dir = at(path);
            if (dir == null || !Files.isDirectory(dir)) {
                return List.of();
            }
            try (Stream<Path> files = Files.list(dir)) {
                return files.map(p -> p.getFileName().toString()).sorted().toList();
            } catch (IOException e) {
                return List.of();
            }
        }

        @Override
        public Path file(String path) {
            return at(path);
        }

        @Override
        public Path directory(String path) {
            return at(path);
        }

        @Override
        public Tree sub(String path) {
            Path dir = at(path);
            return new DirectoryTree(dir == null ? root : dir);
        }
    }

    /** Entries of a jar below {@code prefix} (ending with '/'). Jars need not have entries for directories. */
    record JarTree(LibraryJar jar, String prefix) implements Tree {
        @Override
        public boolean isFile(String path) {
            return !path.isEmpty() && jar.contains(prefix + path);
        }

        @Override
        public boolean isDirectory(String path) {
            String dir = path.isEmpty() ? prefix : prefix + path + "/";
            return jar.entries().stream().anyMatch(name -> name.startsWith(dir) && name.length() > dir.length());
        }

        @Override
        public List<String> children(String path) {
            String dir = path.isEmpty() ? prefix : prefix + path + "/";
            Set<String> names = new TreeSet<>();
            for (String name : jar.entries()) {
                if (name.startsWith(dir) && name.length() > dir.length()) {
                    String rest = name.substring(dir.length());
                    int slash = rest.indexOf('/');
                    names.add(slash < 0 ? rest : rest.substring(0, slash));
                }
            }
            names.remove("");
            return List.copyOf(names);
        }

        @Override
        public Path file(String path) {
            return LibraryFiles.extract(jar.path(), prefix + path);
        }

        @Override
        public Path directory(String path) {
            return null;
        }

        @Override
        public Tree sub(String path) {
            return new JarTree(jar, prefix + path + "/");
        }
    }
}
