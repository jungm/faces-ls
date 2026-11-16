package io.github.jungm.fls;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import io.github.jungm.fls.analysis.FacesDocument.ElSite;
import io.github.jungm.fls.extension.ExtensionHost;
import io.github.jungm.fls.extension.FaceletsPosition;
import io.github.jungm.fls.extension.FaceletsRange;
import io.github.jungm.fls.extension.FacesExtension;
import io.github.jungm.fls.extension.JavaPosition;
import io.github.jungm.fls.extension.JavaRange;
import io.github.jungm.fls.extension.StringArgument;

/**
 * The extension of {@link ExtensionsTest}, loaded from a jar that names it as a service. It answers for EL that
 * starts with {@code ext}, and for every string argument jdtls reports.
 */
public final class RecordingExtension implements FacesExtension {
    static final List<JsonElement> SETTINGS = new CopyOnWriteArrayList<>();
    static final List<Path> CHANGED = new CopyOnWriteArrayList<>();
    static volatile boolean failing;

    private ExtensionHost host;

    @Override
    public String id() {
        return "recording";
    }

    @Override
    public void start(ExtensionHost extensionHost) {
        host = extensionHost;
    }

    @Override
    public String configurationSection() {
        return "recording";
    }

    @Override
    public void configure(JsonElement settings) {
        SETTINGS.add(settings);
    }

    @Override
    public List<String> watchedFiles() {
        return List.of("**/*.messages.*");
    }

    @Override
    public void filesChanged(List<Path> files) {
        CHANGED.addAll(files);
    }

    private static ElSite site(FaceletsPosition position) {
        ElSite site = position.document().elAt(position.offset());
        return site != null && position.document().text().startsWith("ext", site.region().exprStart()) ? site : null;
    }

    private static Location location(String uri, int line) {
        return new Location(uri, new Range(new Position(line, 0), new Position(line, 0)));
    }

    @Override
    public List<Location> definition(FaceletsPosition position) {
        if (failing) {
            throw new IllegalStateException("broken extension");
        }
        return site(position) == null ? List.of() : List.of(location("file:///extension.properties", 7));
    }

    @Override
    public String hover(FaceletsPosition position) {
        return site(position) == null ? null : "from the **extension**";
    }

    @Override
    public List<InlayHint> inlayHints(FaceletsRange range) {
        return range.document().elSites().stream()
                .filter(site -> site.region().start() >= range.start() && site.region().end() <= range.end())
                .map(site -> new InlayHint(range.document().lines().position(site.region().end()), Either.forLeft("hint")))
                .toList();
    }

    @Override
    public List<String> commands() {
        return List.of("recording.sites");
    }

    /** The number of EL expressions in the open document given as first argument. */
    @Override
    public JsonElement executeCommand(String command, List<JsonElement> arguments) {
        var analyzer = host.analyzer(arguments.get(0).getAsString());
        return analyzer == null ? null : new JsonPrimitive(analyzer.document().elSites().size());
    }

    @Override
    public boolean supportsJava() {
        return true;
    }

    @Override
    public List<Location> javaDefinition(JavaPosition position) {
        return host.stringArguments(position.uri(), position.position(), List.of("a.Messages")).stream()
                .map(argument -> location("file:///" + argument.value() + ".properties", argument.index())).toList();
    }

    @Override
    public List<InlayHint> javaInlayHints(JavaRange range) {
        List<StringArgument> arguments = host.stringArguments(range.uri(), null, List.of("a.Messages"));
        return arguments.stream()
                .map(argument -> new InlayHint(argument.range().getEnd(), Either.forLeft(argument.method() + ":" + argument.value())))
                .toList();
    }
}
