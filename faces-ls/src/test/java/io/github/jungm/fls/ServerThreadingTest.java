package io.github.jungm.fls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

/** Requests run concurrently, yet each sees the document as of the changes the client sent before it. */
class ServerThreadingTest {
    @TempDir
    Path workspace;

    private static final String HEADER = "<html xmlns:h=\"jakarta.faces.html\">";

    private final List<Boolean> javaStatus = new CopyOnWriteArrayList<>();
    private FacesLanguageServer server;

    @AfterEach
    void shutDown() {
        if (server != null) {
            server.shutdown().join();
        }
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
                    if (method.getName().equals("javaStatus")) {
                        javaStatus.add(((JsonObject) args[0]).get("connected").getAsBoolean());
                    }
                    return method.getReturnType() == CompletableFuture.class ? CompletableFuture.completedFuture(null) : null;
                });
    }

    private FacesLanguageServer start(Path rendezvous) throws Exception {
        Files.createDirectories(rendezvous);
        FacesLanguageServer started = new FacesLanguageServer();
        started.connect(client());
        InitializeParams params = new InitializeParams();
        params.setWorkspaceFolders(List.of(new WorkspaceFolder(workspace.toUri().toString(), "workspace")));
        JsonObject options = new JsonObject();
        options.addProperty("jdt_rendezvous", rendezvous.toString());
        params.setInitializationOptions(options);
        started.initialize(params).get(10, TimeUnit.SECONDS);
        return started;
    }

    private Path page(String module, String name) throws Exception {
        Files.writeString(Files.createDirectories(workspace.resolve(module)).resolve("pom.xml"), "<project/>");
        Path page = Files.createDirectories(workspace.resolve(module).resolve("src/main/webapp")).resolve(name);
        Files.writeString(page, HEADER + "</html>");
        return page;
    }

    private void open(String uri, String text) {
        server.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "xhtml", 1, text)));
    }

    private List<String> complete(String uri, int line, int character) throws Exception {
        return server.completion(new CompletionParams(new TextDocumentIdentifier(uri), new Position(line, character)))
                .get(10, TimeUnit.SECONDS).getRight().getItems().stream().map(CompletionItem::getLabel).toList();
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        for (int i = 0; i < 400 && !condition.getAsBoolean(); i++) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), what);
    }

    @Test
    void requestRightAfterAChangeSeesTheNewText() throws Exception {
        String uri = page("web", "page.xhtml").toUri().toString();
        server = start(workspace.resolve("rendezvous"));
        open(uri, "<html/>");
        for (int version = 2; version < 30; version++) {
            // only this version has the line, and it alternates between a tag prefix and markup
            boolean tag = version % 2 == 0;
            String text = HEADER + "\n".repeat(version) + (tag ? "<h:" : "<p>");
            server.didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(uri, version),
                    List.of(new TextDocumentContentChangeEvent(text))));
            List<String> labels = complete(uri, version, 3);
            assertEquals(tag, labels.contains("h:inputText"), "version " + version + ": " + labels);
        }
    }

    @Test
    void requestWaitingForJdtlsDoesNotHoldUpOthers() throws Exception {
        Path slow = page("slow", "slow.xhtml");
        Path fast = page("fast", "fast.xhtml");
        Path rendezvous = workspace.resolve("rendezvous");
        try (FakeJdt jdt = new FakeJdt(workspace, rendezvous)) {
            // jdtls takes its time to tell which project the slow page belongs to
            jdt.hold("project", params -> Path.of(params.get("path").getAsString()).startsWith(workspace.resolve("slow")));
            server = start(rendezvous);
            await(() -> javaStatus.contains(true), "connected to faces-jdt");

            String text = HEADER + "\n<h:";
            open(slow.toUri().toString(), text);
            open(fast.toUri().toString(), text);
            CompletableFuture<Hover> waiting = server.hover(
                    new HoverParams(new TextDocumentIdentifier(slow.toUri().toString()), new Position(1, 2)));
            await(() -> jdt.requests("project") > 0, "project of the slow page requested");

            assertTrue(complete(fast.toUri().toString(), 1, 3).contains("h:inputText"));
            assertFalse(waiting.isDone());

            jdt.release("project");
            waiting.get(20, TimeUnit.SECONDS);
            assertTrue(complete(slow.toUri().toString(), 1, 3).contains("h:inputText"));
        }
    }
}
