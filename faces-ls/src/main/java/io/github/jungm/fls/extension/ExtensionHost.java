package io.github.jungm.fls.extension;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import org.eclipse.lsp4j.Position;

import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.index.FaceletsIndex;
import io.github.jungm.fls.project.ProjectModel;

/** What faces-ls offers an extension besides the model that comes with each request. */
public interface ExtensionHost {

    List<Path> workspaceRoots();

    /**
     * The analyzer of a document, as requests on it get one: of an open document with its unsaved text, else of a
     * Facelets file of the index. Null for any other document.
     */
    ElAnalyzer analyzer(String uri);

    /** The Facelets files of the workspace and its jars with what uses what; null while it is being built. */
    FaceletsIndex.Snapshot index();

    /** The project of a file. */
    ProjectModel project(Path file);

    /** Whether jdtls answers: without it there are no types, and {@link #stringArguments} is empty. */
    boolean javaConnected();

    /** Asks the client to request inlay hints again, after something they are computed from has changed. */
    void refreshInlayHints();

    /**
     * The arguments of method calls in a Java document whose value is a compile-time {@code String} constant, as
     * jdtls sees the document (unsaved edits included). Empty without jdtls.
     *
     * @param position      only the argument a string literal at this position belongs to; null for all arguments of
     *                      the document
     * @param receiverTypes only calls on one of these types or their subtypes (fully qualified names); empty for all
     */
    List<StringArgument> stringArguments(String uri, Position position, Collection<String> receiverTypes);
}
