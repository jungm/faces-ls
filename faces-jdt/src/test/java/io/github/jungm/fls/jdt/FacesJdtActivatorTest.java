package io.github.jungm.fls.jdt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class FacesJdtActivatorTest {
    @Test
    void rendezvousDirectoryIsNextToTheJar() {
        Path expected = Path.of("/opt/ext/faces-jdt-rendezvous");
        assertEquals(expected, FacesJdtActivator.rendezvousDirectory("reference:file:/opt/ext/faces-jdt.jar"));
        assertEquals(expected, FacesJdtActivator.rendezvousDirectory("file:/opt/ext/faces-jdt.jar"));
        assertEquals(expected, FacesJdtActivator.rendezvousDirectory("/opt/ext/faces-jdt.jar"));
    }

    @Test
    void locationsMayContainSpaces() {
        assertEquals(Path.of("/opt/my ext/faces-jdt-rendezvous"),
                FacesJdtActivator.rendezvousDirectory("reference:file:/opt/my ext/faces-jdt.jar"));
        assertEquals(Path.of("/opt/my ext/faces-jdt-rendezvous"),
                FacesJdtActivator.rendezvousDirectory("reference:file:/opt/my%20ext/faces-jdt.jar"));
    }

    @Test
    void relativeLocationsAreResolved() {
        assertEquals(Path.of("lib").toAbsolutePath().resolve("faces-jdt-rendezvous"),
                FacesJdtActivator.rendezvousDirectory("reference:file:lib/faces-jdt.jar"));
    }
}
