package io.github.jungm.fls.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Jar entries are extracted into a per-user cache directory that only its owner can access. */
class LibraryFilesTest {
    @TempDir
    Path dir;

    @Test
    void usesThePlatformCacheDirectory() {
        Map<String, String> env = Map.of("XDG_CACHE_HOME", "/xdg/cache", "LOCALAPPDATA", "C:\\Users\\u\\AppData\\Local");
        assertEquals(Path.of("/xdg/cache"), LibraryFiles.cacheRoot("Linux", env::get, "/home/u"));
        assertEquals(Path.of("/home/u", ".cache"), LibraryFiles.cacheRoot("Linux", k -> null, "/home/u"));
        assertEquals(Path.of("/home/u", ".cache"), LibraryFiles.cacheRoot("Linux", k -> "relative", "/home/u"));
        assertEquals(Path.of("/Users/u", "Library", "Caches"), LibraryFiles.cacheRoot("Mac OS X", env::get, "/Users/u"));
        assertEquals(Path.of(env.get("LOCALAPPDATA")), LibraryFiles.cacheRoot("Windows 11", env::get, "C:\\Users\\u"));
        assertEquals(Path.of("C:\\Users\\u", "AppData", "Local"), LibraryFiles.cacheRoot("Windows 11", k -> null, "C:\\Users\\u"));
    }

    private Path jar(String content) throws Exception {
        Path jar = dir.resolve("lib.jar");
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("META-INF/resources/lib/box.xhtml"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return jar;
    }

    @Test
    void extractsIntoAnOwnerOnlyDirectoryPerJarVersion() throws Exception {
        Path jar = jar("<first/>");
        Path extracted = LibraryFiles.extract(jar, "META-INF/resources/lib/box.xhtml");
        assertNotNull(extracted);
        Path cache = LibraryFiles.cacheDirectory();
        assertTrue(extracted.startsWith(cache), extracted.toString());
        assertFalse(cache.startsWith(Path.of(System.getProperty("java.io.tmpdir"))), cache.toString());
        assertEquals("<first/>", Files.readString(extracted));
        String jarDirectory = cache.relativize(extracted).getName(0).toString();
        assertTrue(jarDirectory.matches("lib\\.jar-[0-9a-f]{32}"), jarDirectory);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(cache.getParent())));
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(cache.resolve(jarDirectory))));
        }

        // another version of the jar gets its own directory
        jar("<second/>");
        Files.setLastModifiedTime(jar, FileTime.fromMillis(Files.getLastModifiedTime(jar).toMillis() + 5000));
        Path second = LibraryFiles.extract(jar, "META-INF/resources/lib/box.xhtml");
        assertNotEquals(extracted, second);
        assertEquals("<second/>", Files.readString(second));

        assertNull(LibraryFiles.extract(jar, "../../escape.xhtml"));
    }
}
