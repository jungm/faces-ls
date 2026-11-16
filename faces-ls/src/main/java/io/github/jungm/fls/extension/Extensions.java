package io.github.jungm.fls.extension;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.Location;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The extensions of this server. Calls go to every extension; one that fails is logged and skipped, so that it
 * cannot take the answers of faces-ls or of the other extensions with it.
 */
public final class Extensions {
    private static final Logger LOG = Logger.getLogger(Extensions.class.getName());

    private final List<FacesExtension> extensions;

    private Extensions(List<FacesExtension> extensions) {
        this.extensions = List.copyOf(extensions);
    }

    public static Extensions of(List<FacesExtension> extensions) {
        return new Extensions(extensions);
    }

    /**
     * Loads the extensions of the jars in {@code {"extensions": ["/path/to/extension.jar"]}}. The jars see the
     * classes of faces-ls; a jar that is missing or cannot be loaded is logged and left out.
     */
    public static Extensions load(JsonElement settings) {
        List<URL> jars = new ArrayList<>();
        if (settings instanceof JsonObject object && object.get("extensions") instanceof JsonArray paths) {
            for (JsonElement element : paths) {
                if (!element.isJsonPrimitive() || element.getAsString().isBlank()) {
                    continue;
                }
                Path jar = Path.of(element.getAsString());
                if (!Files.isRegularFile(jar)) {
                    LOG.warning("extension " + jar + " not found");
                    continue;
                }
                try {
                    jars.add(jar.toUri().toURL());
                } catch (MalformedURLException e) {
                    LOG.log(Level.WARNING, "cannot load extension " + jar, e);
                }
            }
        }
        if (jars.isEmpty()) {
            return new Extensions(List.of());
        }
        // stays open for the lifetime of the server: extensions load their classes lazily
        ClassLoader loader = new URLClassLoader("faces-ls-extensions", jars.toArray(URL[]::new),
                Extensions.class.getClassLoader());
        List<FacesExtension> found = new ArrayList<>();
        var providers = ServiceLoader.load(FacesExtension.class, loader).iterator();
        while (true) {
            try {
                if (!providers.hasNext()) {
                    break;
                }
                FacesExtension extension = providers.next();
                found.add(extension);
                LOG.info("extension " + extension.id() + " (" + extension.getClass().getName() + ")");
            } catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
                LOG.log(Level.WARNING, "cannot load an extension of " + jars, e);
            }
        }
        if (found.isEmpty()) {
            LOG.warning("no extension in " + jars + " (META-INF/services/" + FacesExtension.class.getName() + ")");
        }
        return new Extensions(found);
    }

    public boolean isEmpty() {
        return extensions.isEmpty();
    }

    public List<FacesExtension> all() {
        return extensions;
    }

    public boolean supportsJava() {
        return extensions.stream().anyMatch(extension -> answer(extension, "supportsJava", FacesExtension::supportsJava, false));
    }

    public void start(ExtensionHost host) {
        each("start", extension -> extension.start(host));
    }

    /** The glob patterns the extensions want watched. */
    public Set<String> watchedFiles() {
        Set<String> patterns = new LinkedHashSet<>();
        each("watchedFiles", extension -> patterns.addAll(extension.watchedFiles()));
        return patterns;
    }

    public void filesChanged(List<Path> files) {
        each("filesChanged", extension -> extension.filesChanged(files));
    }

    /** Hands an extension its settings; a failure is logged. */
    public void configure(FacesExtension extension, JsonElement settings) {
        answer(extension, "configure", e -> {
            e.configure(settings);
            return null;
        }, null);
    }

    /** The first non-empty answer. */
    public List<Location> definition(FaceletsPosition position) {
        for (FacesExtension extension : extensions) {
            List<Location> locations = answer(extension, "definition", e -> e.definition(position), List.of());
            if (locations != null && !locations.isEmpty()) {
                return locations;
            }
        }
        return List.of();
    }

    /** The first answer, or null. */
    public String hover(FaceletsPosition position) {
        for (FacesExtension extension : extensions) {
            String markdown = answer(extension, "hover", e -> e.hover(position), null);
            if (markdown != null && !markdown.isBlank()) {
                return markdown;
            }
        }
        return null;
    }

    public List<InlayHint> inlayHints(FaceletsRange range) {
        return all("inlayHints", extension -> extension.inlayHints(range));
    }

    public List<Location> javaDefinition(JavaPosition position) {
        return all("javaDefinition", extension -> extension.supportsJava() ? extension.javaDefinition(position) : List.of());
    }

    public List<InlayHint> javaInlayHints(JavaRange range) {
        return all("javaInlayHints", extension -> extension.supportsJava() ? extension.javaInlayHints(range) : List.of());
    }

    private <T> List<T> all(String what, Function<FacesExtension, List<T>> call) {
        List<T> result = new ArrayList<>();
        for (FacesExtension extension : extensions) {
            List<T> answer = answer(extension, what, call, List.of());
            if (answer != null) {
                result.addAll(answer);
            }
        }
        return result;
    }

    private void each(String what, Consumer<FacesExtension> call) {
        for (FacesExtension extension : extensions) {
            answer(extension, what, e -> {
                call.accept(e);
                return null;
            }, null);
        }
    }

    private static <T> T answer(FacesExtension extension, String what, Function<FacesExtension, T> call, T fallback) {
        try {
            return call.apply(extension);
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (RuntimeException | LinkageError e) {
            LOG.log(Level.WARNING, what + " of extension " + extension.getClass().getName() + " failed", e);
            return fallback;
        }
    }
}
