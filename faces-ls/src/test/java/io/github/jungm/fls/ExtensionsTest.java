package io.github.jungm.fls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.jungm.fls.extension.Extensions;
import io.github.jungm.fls.extension.FacesExtension;

/** Extensions are loaded from the jars of the settings and take part in the requests of the server. */
class ExtensionsTest {
    @TempDir
    Path workspace;

    private static final String PAGE = "<html xmlns:h=\"jakarta.faces.html\">\n"
            + "<h:outputText value=\"#{ext['a.key']}\"/>\n"
            + "<h:outputText value=\"#{other}\"/>\n"
            + "</html>";

    private final List<Boolean> javaStatus = new CopyOnWriteArrayList<>();
    private final List<String> watched = new CopyOnWriteArrayList<>();
    private final List<String> configurationSections = new CopyOnWriteArrayList<>();
    private FacesLanguageServer server;

    @BeforeEach
    void reset() {
        RecordingExtension.SETTINGS.clear();
        RecordingExtension.CHANGED.clear();
        RecordingExtension.failing = false;
    }

    @AfterEach
    void shutDown() {
        if (server != null) {
            server.shutdown().join();
        }
    }

    /** A jar that only names {@link RecordingExtension}, which the server's own class loader has. */
    private Path extensionJar() throws Exception {
        Path jar = workspace.resolve("extension.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream entries = new JarOutputStream(out)) {
            entries.putNextEntry(new JarEntry("META-INF/services/" + FacesExtension.class.getName()));
            entries.write(RecordingExtension.class.getName().getBytes(StandardCharsets.UTF_8));
            entries.closeEntry();
        }
        return jar;
    }

    private FacesLanguageClient client() {
        return (FacesLanguageClient) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {FacesLanguageClient.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "test client";
                        };
                    }
                    switch (method.getName()) {
                        case "javaStatus" -> javaStatus.add(((JsonObject) args[0]).get("connected").getAsBoolean());
                        case "registerCapability" -> ((RegistrationParams) args[0]).getRegistrations().forEach(registration -> {
                            if (registration.getRegisterOptions() instanceof DidChangeWatchedFilesRegistrationOptions options) {
                                options.getWatchers().forEach(watcher -> watched.add(watcher.getGlobPattern().getLeft()));
                            }
                        });
                        case "configuration" -> {
                            String section = ((ConfigurationParams) args[0]).getItems().get(0).getSection();
                            configurationSections.add(section);
                            return CompletableFuture.completedFuture(List.<Object>of(JsonParser.parseString("{\"locale\": \"de\"}")));
                        }
                        default -> {
                            // answered with nothing
                        }
                    }
                    return method.getReturnType() == CompletableFuture.class ? CompletableFuture.completedFuture(null) : null;
                });
    }

    private InitializeResult start(Path rendezvous, boolean withExtension) throws Exception {
        Files.createDirectories(rendezvous);
        server = new FacesLanguageServer();
        server.connect(client());
        InitializeParams params = new InitializeParams();
        params.setWorkspaceFolders(List.of(new WorkspaceFolder(workspace.toUri().toString(), "workspace")));
        WorkspaceClientCapabilities workspaceCapabilities = new WorkspaceClientCapabilities();
        workspaceCapabilities.setConfiguration(true);
        params.setCapabilities(new ClientCapabilities(workspaceCapabilities, null, null));
        JsonObject options = new JsonObject();
        options.addProperty("jdt_rendezvous", rendezvous.toString());
        if (withExtension) {
            JsonArray jars = new JsonArray();
            jars.add(extensionJar().toString());
            jars.add(workspace.resolve("missing.jar").toString());
            options.add("extensions", jars);
        }
        params.setInitializationOptions(options);
        InitializeResult result = server.initialize(params).get(10, TimeUnit.SECONDS);
        server.initialized(new InitializedParams());
        return result;
    }

    private String page() throws Exception {
        Files.writeString(Files.createDirectories(workspace.resolve("web")).resolve("pom.xml"), "<project/>");
        Path page = Files.createDirectories(workspace.resolve("web/src/main/webapp")).resolve("page.xhtml");
        Files.writeString(page, PAGE);
        String uri = page.toUri().toString();
        server.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "xhtml", 1, PAGE)));
        return uri;
    }

    private List<? extends Location> definition(String uri, int line, int character) throws Exception {
        return server.definition(new DefinitionParams(new TextDocumentIdentifier(uri), new Position(line, character)))
                .get(10, TimeUnit.SECONDS).getLeft();
    }

    private List<InlayHint> inlayHints(String uri) throws Exception {
        return server.inlayHint(new InlayHintParams(new TextDocumentIdentifier(uri),
                new Range(new Position(0, 0), new Position(100, 0)))).get(10, TimeUnit.SECONDS);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        for (int i = 0; i < 400 && !condition.getAsBoolean(); i++) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), what);
    }

    @Test
    void withoutExtensionsNothingIsAnnounced() throws Exception {
        InitializeResult result = start(workspace.resolve("rendezvous"), false);
        assertNull(result.getCapabilities().getInlayHintProvider());
        assertNull(result.getCapabilities().getExperimental());
        assertTrue(inlayHints(page()).isEmpty());
        assertTrue(Extensions.load(null).isEmpty());
    }

    @Test
    void extensionAnswersFaceletsRequests() throws Exception {
        InitializeResult result = start(workspace.resolve("rendezvous"), true);
        assertEquals(Boolean.TRUE, result.getCapabilities().getInlayHintProvider().getLeft());
        JsonObject faces = ((JsonObject) result.getCapabilities().getExperimental()).getAsJsonObject("faces");
        assertTrue(faces.get("javaDefinition").getAsBoolean());
        assertTrue(faces.get("javaInlayHints").getAsBoolean());

        String uri = page();
        List<? extends Location> locations = definition(uri, 1, 30);
        assertEquals(List.of("file:///extension.properties"), locations.stream().map(Location::getUri).toList());
        assertEquals("from the **extension**", server.hover(new HoverParams(new TextDocumentIdentifier(uri), new Position(1, 30)))
                .get(10, TimeUnit.SECONDS).getContents().getRight().getValue());
        // EL the extension does not know is left to faces-ls
        assertTrue(definition(uri, 2, 27).isEmpty());
        assertEquals(List.of("hint", "hint"), inlayHints(uri).stream().map(hint -> hint.getLabel().getLeft()).toList());
    }

    @Test
    void extensionAnswersItsCommands() throws Exception {
        InitializeResult result = start(workspace.resolve("rendezvous"), true);
        assertTrue(result.getCapabilities().getExecuteCommandProvider().getCommands().contains("recording.sites"));
        String uri = page();
        Object sites = server.executeCommand(new ExecuteCommandParams("recording.sites", List.of(new JsonPrimitive(uri))))
                .get(10, TimeUnit.SECONDS);
        assertEquals(new JsonPrimitive(2), sites);
        // a file that is neither open nor indexed, and a command nobody has
        assertNull(server.executeCommand(new ExecuteCommandParams("recording.sites", List.of(new JsonPrimitive("file:///none.xhtml"))))
                .get(10, TimeUnit.SECONDS));
        assertNull(server.executeCommand(new ExecuteCommandParams("unknown", List.of())).get(10, TimeUnit.SECONDS));
    }

    @Test
    void failingExtensionLeavesTheAnswerToFacesLs() throws Exception {
        start(workspace.resolve("rendezvous"), true);
        String uri = page();
        RecordingExtension.failing = true;
        assertTrue(definition(uri, 1, 30).isEmpty());
    }

    @Test
    void extensionIsConfiguredAndToldAboutFiles() throws Exception {
        start(workspace.resolve("rendezvous"), true);
        await(() -> !RecordingExtension.SETTINGS.isEmpty(), "extension configured");
        assertEquals(List.of("recording"), configurationSections);
        assertEquals("de", RecordingExtension.SETTINGS.get(0).getAsJsonObject().get("locale").getAsString());
        await(() -> watched.contains("**/*.messages.*"), "extension files watched");
        assertTrue(watched.contains("**/*.xhtml"));

        Path messages = workspace.resolve("web/src/main/resources/main.messages.de");
        Path skipped = workspace.resolve("web/target/classes/main.messages.de");
        server.didChangeWatchedFiles(new DidChangeWatchedFilesParams(List.of(
                new FileEvent(messages.toUri().toString(), FileChangeType.Changed),
                new FileEvent(skipped.toUri().toString(), FileChangeType.Changed))));
        await(() -> !RecordingExtension.CHANGED.isEmpty(), "extension told about the file");
        assertEquals(List.of(messages), List.copyOf(RecordingExtension.CHANGED));

        server.didChangeConfiguration(new org.eclipse.lsp4j.DidChangeConfigurationParams(new JsonObject()));
        await(() -> RecordingExtension.SETTINGS.size() == 2, "extension configured again");
    }

    @Test
    void extensionAnswersJavaRequestsThroughJdtls() throws Exception {
        Path rendezvous = workspace.resolve("rendezvous");
        Files.writeString(Files.createDirectories(workspace.resolve("web")).resolve("pom.xml"), "<project/>");
        Path source = Files.createDirectories(workspace.resolve("web/src/main/java/a")).resolve("Bean.java");
        Files.writeString(source, "package a; class Bean {}");
        try (FakeJdt jdt = new FakeJdt(workspace, rendezvous)) {
            JsonElement arguments = JsonParser.parseString("[{\"value\": \"a.key\", \"literal\": true, \"method\": \"get\","
                    + " \"index\": 1, \"argumentCount\": 2, \"receiverTypes\": [\"a.Messages\", \"java.lang.Object\"],"
                    + " \"range\": {\"start\": {\"line\": 3, \"character\": 4}, \"end\": {\"line\": 3, \"character\": 11}}}]");
            jdt.stringArguments(arguments);
            start(rendezvous, true);
            await(() -> javaStatus.contains(true), "connected to faces-jdt");

            String uri = source.toUri().toString();
            List<? extends Location> locations = definition(uri, 3, 6);
            assertEquals(List.of("file:///a.key.properties"), locations.stream().map(Location::getUri).toList());
            assertEquals(1, locations.get(0).getRange().getStart().getLine());
            List<InlayHint> hints = inlayHints(uri);
            assertEquals(List.of("get:a.key"), hints.stream().map(hint -> hint.getLabel().getLeft()).toList());
            assertEquals(new Position(3, 11), hints.get(0).getPosition());
            // library classes have no project
            assertTrue(definition("jdt://contents/a.jar/a/Bean.class", 3, 6).isEmpty());
        }
    }
}
