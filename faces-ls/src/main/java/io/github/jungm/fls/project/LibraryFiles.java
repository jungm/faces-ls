package io.github.jungm.fls.project;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Files inside library jars. Entries are extracted into a read-only cache in the user's cache directory so that
 * editors can open them (go to definition into a composite component or a bundle shipped in a jar).
 */
public final class LibraryFiles {
    private static final Logger LOG = Logger.getLogger(LibraryFiles.class.getName());
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rwx------");
    private static final Path CACHE = cacheRoot(System.getProperty("os.name", ""), System::getenv,
            System.getProperty("user.home")).resolve("faces-ls").resolve("libraries");

    private LibraryFiles() {
    }

    /**
     * The per-user cache directory: {@code %LOCALAPPDATA%} on Windows, {@code ~/Library/Caches} on macOS, else
     * {@code $XDG_CACHE_HOME} or {@code ~/.cache}.
     */
    static Path cacheRoot(String osName, UnaryOperator<String> env, String home) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            String localAppData = env.apply("LOCALAPPDATA");
            return localAppData != null && !localAppData.isBlank() ? Path.of(localAppData)
                    : Path.of(home, "AppData", "Local");
        }
        if (os.startsWith("mac")) {
            return Path.of(home, "Library", "Caches");
        }
        String xdg = env.apply("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()) {
            return Path.of(xdg);
        }
        return Path.of(home, ".cache");
    }

    /** The directory extracted entries are cached in. */
    static Path cacheDirectory() {
        return CACHE;
    }

    /** Extracts an entry (if not yet extracted for this jar version) and returns the extracted file, or null. */
    public static Path extract(Path jar, String entry) {
        try {
            Path absolute = jar.toAbsolutePath().normalize();
            long modified = Files.getLastModifiedTime(absolute).toMillis();
            String jarKey = absolute.getFileName() + "-" + digest(absolute + ":" + modified + ":" + Files.size(absolute));
            Path target = CACHE.resolve(jarKey).resolve(entry).normalize();
            if (!target.startsWith(CACHE.resolve(jarKey))) {
                return null;
            }
            if (Files.isRegularFile(target)) {
                return target;
            }
            try (ZipFile zip = new ZipFile(absolute.toFile())) {
                ZipEntry zipEntry = zip.getEntry(entry);
                if (zipEntry == null) {
                    return null;
                }
                createDirectories(target.getParent());
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
        Path base = CACHE.resolve("faces-ls-" + (version == null ? "dev" : version));
        Path target = base.resolve(resource.replaceFirst("^/", "")).normalize();
        if (!target.startsWith(base)) {
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
            createDirectories(target.getParent());
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

    /**
     * Creates {@code dir} and its missing parents. The cache directory and everything created below it are
     * accessible to the owner only where the file system has POSIX permissions.
     */
    private static void createDirectories(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            return;
        }
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        Path root = CACHE.getParent();
        if (!Files.isDirectory(root)) {
            Files.createDirectories(root.getParent());
            create(root, posix);
        }
        if (posix) {
            Files.setPosixFilePermissions(root, OWNER_ONLY);
        }
        Path current = root;
        for (Path name : root.relativize(dir)) {
            current = current.resolve(name);
            if (!Files.isDirectory(current)) {
                create(current, posix);
            }
        }
    }

    private static void create(Path dir, boolean posix) throws IOException {
        try {
            if (posix) {
                Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            } else {
                Files.createDirectory(dir);
            }
        } catch (FileAlreadyExistsException e) {
            if (!Files.isDirectory(dir)) {
                throw e;
            }
        }
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static boolean isJar(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        return (name.endsWith(".jar") || name.endsWith(".zip")) && Files.isRegularFile(path);
    }
}
