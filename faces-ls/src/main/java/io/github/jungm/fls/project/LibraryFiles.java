package io.github.jungm.fls.project;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Files inside library jars. Entries are extracted into a read-only cache so that editors can open them (go to
 * definition into a composite component or a bundle shipped in a jar).
 */
public final class LibraryFiles {
    private static final Logger LOG = Logger.getLogger(LibraryFiles.class.getName());
    private static final Path CACHE = Path.of(System.getProperty("java.io.tmpdir"), "faces-ls-" + System.getProperty("user.name"),
            "libraries");

    private LibraryFiles() {
    }

    private record Key(Path jar, long modified) {
    }

    private static final Map<Key, Set<String>> NAMES_BY_VERSION = new ConcurrentHashMap<>();

    /** Names of all entries in a jar, cached per jar version. */
    public static Set<String> entries(Path jar) {
        long modified;
        try {
            modified = Files.getLastModifiedTime(jar).toMillis();
        } catch (IOException e) {
            return Set.of();
        }
        return NAMES_BY_VERSION.computeIfAbsent(new Key(jar, modified), key -> {
            Set<String> names = new LinkedHashSet<>();
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                zip.stream().map(ZipEntry::getName).forEach(names::add);
            } catch (IOException e) {
                LOG.log(Level.FINE, "cannot open " + jar, e);
            }
            return Collections.unmodifiableSet(names);
        });
    }

    public static boolean contains(Path jar, String entry) {
        return entries(jar).contains(entry);
    }

    /** Extracts an entry (if not yet extracted for this jar version) and returns the extracted file, or null. */
    public static Path extract(Path jar, String entry) {
        try {
            long modified = Files.getLastModifiedTime(jar).toMillis();
            String jarKey = jar.getFileName() + "-" + Integer.toHexString((jar.toAbsolutePath() + ":" + modified).hashCode());
            Path target = CACHE.resolve(jarKey).resolve(entry).normalize();
            if (!target.startsWith(CACHE)) {
                return null;
            }
            if (Files.isRegularFile(target)) {
                return target;
            }
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry zipEntry = zip.getEntry(entry);
                if (zipEntry == null) {
                    return null;
                }
                Files.createDirectories(target.getParent());
                Path temp = Files.createTempFile(target.getParent(), "extract", ".tmp");
                try (InputStream input = zip.getInputStream(zipEntry)) {
                    Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                target.toFile().setReadOnly();
                return target;
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "cannot extract " + entry + " from " + jar, e);
            return null;
        }
    }

    /** Extracts a resource bundled with faces-ls (such as the standard taglibs) and returns the file, or null. */
    public static Path extractResource(String resource) {
        String version = LibraryFiles.class.getPackage().getImplementationVersion();
        Path target = CACHE.resolve("faces-ls-" + (version == null ? "dev" : version)).resolve(resource.replaceFirst("^/", ""))
                .normalize();
        if (!target.startsWith(CACHE)) {
            return null;
        }
        try (InputStream input = LibraryFiles.class.getResourceAsStream(resource)) {
            if (input == null) {
                return null;
            }
            byte[] content = input.readAllBytes();
            if (Files.isRegularFile(target) && Files.size(target) == content.length) {
                return target;
            }
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "extract", ".tmp");
            Files.write(temp, content);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            target.toFile().setReadOnly();
            return target;
        } catch (IOException e) {
            LOG.log(Level.FINE, "cannot extract " + resource, e);
            return null;
        }
    }

    public static boolean isJar(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        return (name.endsWith(".jar") || name.endsWith(".zip")) && Files.isRegularFile(path);
    }
}
