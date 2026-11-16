package io.github.jungm.fls.extension;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.Location;

import com.google.gson.JsonElement;

/**
 * An extension of faces-ls: support for a framework on top of Jakarta Faces that faces-ls itself does not know.
 * Extensions are found with {@link java.util.ServiceLoader} in the jars of the {@code extensions} setting, which is
 * read once, when the server starts.
 *
 * <p>An extension is compiled against faces-ls and sees its model as it is ({@link io.github.jungm.fls.analysis},
 * {@link io.github.jungm.fls.el}, {@link io.github.jungm.fls.project}, {@link io.github.jungm.fls.index}); that model
 * changes between releases without notice, so an extension is built for the faces-ls it runs in.
 *
 * <p>All methods but {@link #start} are called from several threads at once. A method that throws is logged and
 * counts as having no answer.
 */
public interface FacesExtension {

    /** A short name, for the log. */
    String id();

    /** Called once, before any other method. */
    default void start(ExtensionHost host) {
    }

    /**
     * The section of the client's settings this extension reads, or null. faces-ls asks the client for it
     * ({@code workspace/configuration}) after the start and whenever the client reports changed settings, and hands
     * the answer to {@link #configure}.
     */
    default String configurationSection() {
        return null;
    }

    /** The settings of {@link #configurationSection}; JSON null when the client has none. */
    default void configure(JsonElement settings) {
    }

    /** Glob patterns of files to watch besides those faces-ls watches itself, e.g. {@code **}{@code /*.messages.*}. */
    default List<String> watchedFiles() {
        return List.of();
    }

    /** Watched files (those of faces-ls included) were created, changed or deleted. */
    default void filesChanged(List<Path> files) {
    }

    /**
     * The definitions of what is at a position of a Facelets document. A non-empty answer replaces that of faces-ls.
     */
    default List<Location> definition(FaceletsPosition position) {
        return List.of();
    }

    /** Markdown for a hover at a position of a Facelets document, or null. An answer replaces that of faces-ls. */
    default String hover(FaceletsPosition position) {
        return null;
    }

    /** Inlay hints of a range of a Facelets document. */
    default List<InlayHint> inlayHints(FaceletsRange range) {
        return List.of();
    }

    /**
     * Whether the extension answers {@link #javaDefinition} and {@link #javaInlayHints}. faces-ls then announces in
     * its capabilities ({@code experimental.faces.javaDefinition}, {@code experimental.faces.javaInlayHints}) that
     * clients may send it those requests for Java documents too.
     */
    default boolean supportsJava() {
        return false;
    }

    /** The definitions of what is at a position of a Java document. Editors add them to those of jdtls. */
    default List<Location> javaDefinition(JavaPosition position) {
        return List.of();
    }

    /** Inlay hints of a range of a Java document. Editors add them to those of jdtls. */
    default List<InlayHint> javaInlayHints(JavaRange range) {
        return List.of();
    }
}
