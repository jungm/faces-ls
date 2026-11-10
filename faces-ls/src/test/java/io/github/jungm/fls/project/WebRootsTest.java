package io.github.jungm.fls.project;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Files a build copies into its output directories are not sources. */
class WebRootsTest {
    private static final Path ROOT = Path.of("/work/build/app");

    @Test
    void buildOutputBelowTheWorkspaceRootIsSkipped() {
        List<Path> roots = List.of(ROOT);
        assertTrue(WebRoots.inSkippedDirectory(roots, ROOT.resolve("web/target/classes/WEB-INF/web.xml")));
        assertTrue(WebRoots.inSkippedDirectory(roots,
                ROOT.resolve("web/target/classes/META-INF/resources/lib/button.xhtml")));
        assertTrue(WebRoots.inSkippedDirectory(roots, ROOT.resolve(".git/x.taglib.xml")));
    }

    @Test
    void sourcesAreNotSkippedEvenWhenTheWorkspaceLiesBelowASkippedName() {
        List<Path> roots = List.of(ROOT);
        assertFalse(WebRoots.inSkippedDirectory(roots, ROOT.resolve("web/src/main/webapp/WEB-INF/web.xml")));
        assertFalse(WebRoots.inSkippedDirectory(roots,
                ROOT.resolve("web/src/main/resources/META-INF/resources/lib/button.xhtml")));
        assertFalse(WebRoots.inSkippedDirectory(roots, ROOT.resolve("index.xhtml")));
    }

    @Test
    void filesOutsideTheWorkspaceAreNotSkipped() {
        assertFalse(WebRoots.inSkippedDirectory(List.of(ROOT), Path.of("/elsewhere/target/web.xml")));
    }
}
