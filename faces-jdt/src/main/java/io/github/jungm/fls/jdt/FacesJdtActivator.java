package io.github.jungm.fls.jdt;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * Starts the socket server that faces-ls connects to. jdtls starts bundles listed in its {@code bundles}
 * initialization option eagerly, so this runs when jdtls initializes.
 */
public class FacesJdtActivator implements BundleActivator {
    private JdtServer server;

    @Override
    public void start(BundleContext context) throws Exception {
        Path rendezvous = rendezvousDirectory(context.getBundle().getLocation());
        String version = context.getBundle().getVersion().toString();
        server = new JdtServer(rendezvous, version);
        server.start();
    }

    @Override
    public void stop(BundleContext context) {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    /**
     * The rendezvous directory sits next to the bundle jar, where editor extensions also point faces-ls.
     * jdtls installs bundles from locations like {@code reference:file:/path/faces-jdt.jar}.
     */
    static Path rendezvousDirectory(String bundleLocation) {
        String location = bundleLocation;
        if (location.startsWith("reference:")) {
            location = location.substring("reference:".length());
        }
        Path jar;
        if (location.startsWith("file:")) {
            jar = Paths.get(URI.create(location.replace(" ", "%20")));
        } else {
            jar = Paths.get(location);
        }
        Path parent = jar.toAbsolutePath().getParent();
        return parent.resolve("faces-jdt-rendezvous");
    }
}
