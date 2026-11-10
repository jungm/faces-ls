package io.github.jungm.fls;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.FileSystemWatcher;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensRangeParams;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.ShowDocumentParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.FacesDocument;
import io.github.jungm.fls.features.CodeActionProvider;
import io.github.jungm.fls.features.CompletionProvider;
import io.github.jungm.fls.features.DefinitionProvider;
import io.github.jungm.fls.features.DiagnosticsProvider;
import io.github.jungm.fls.features.HoverProvider;
import io.github.jungm.fls.features.JavaUsages;
import io.github.jungm.fls.features.SemanticTokensProvider;
import io.github.jungm.fls.features.UsageProvider;
import io.github.jungm.fls.index.FaceletsIndex;
import io.github.jungm.fls.java.JdtClient;
import io.github.jungm.fls.project.ProjectManager;
import io.github.jungm.fls.project.ProjectModel;
import io.github.jungm.fls.project.WebRoots;
import io.github.jungm.fls.taglib.TagLibrary;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The faces-ls language server.
 *
 * <p>lsp4j delivers notifications in order on its reader thread. Document changes take effect there, so every
 * request sees the text of all changes the client sent before it; what follows from a change (index update,
 * diagnostics) runs later. Requests and diagnostics run on a small pool ({@link RequestPool}), so that one request
 * waiting for jdtls does not hold up the others, and a cancelled request is dropped. Follow-up work of notifications
 * runs in order on one thread ({@link #worker}).
 *
 * <p>Request threads share the documents, the projects and the Facelets index, which are safe for concurrent use:
 * documents and index snapshots are immutable, and the project models publish new state as a whole and keep their
 * caches in concurrent maps. Analyzers and providers are created per request. The fields set in
 * {@link #initialize} are written before any other thread uses them.
 */
public final class FacesLanguageServer implements LanguageServer, LanguageClientAware, TextDocumentService, WorkspaceService {
    private static final Logger LOG = Logger.getLogger(FacesLanguageServer.class.getName());

    /** lsp4j's own Gson, for LSP types that cross the faces-jdt socket. */
    private static final Gson LSP_JSON = new MessageJsonHandler(Map.of()).getGson();
    private static final int REQUEST_THREADS = 4;

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(Threads.daemon("faces-ls-worker"));
    private final RequestPool requests = new RequestPool(REQUEST_THREADS);
    private final Map<String, FacesDocument> documents = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingDiagnostics = new ConcurrentHashMap<>();
    /** Per document, the latest diagnostics run; a run that finishes after a later one started does not publish. */
    private final Map<String, Long> diagnosticsRuns = new ConcurrentHashMap<>();
    private final AtomicLong diagnosticsSequence = new AtomicLong();
    /** Orders publishing diagnostics with closing documents. */
    private final Object publishLock = new Object();
    private LanguageClient client;
    private JdtClient jdt;
    private ProjectManager projects;
    private List<Path> roots = List.of();
    private volatile FacesSettings settings = FacesSettings.defaults();
    private FaceletsIndex index;
    private Path rendezvous;
    private boolean semanticTokensRefresh;
    private ScheduledFuture<?> pendingRefresh;

    @Override
    public void connect(LanguageClient languageClient) {
        this.client = languageClient;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        JsonElement options = settingsOf(params.getInitializationOptions());
        configureLogging(options);
        settings = FacesSettings.from(options);
        List<Path> workspaceRoots = new ArrayList<>();
        if (params.getWorkspaceFolders() != null) {
            for (WorkspaceFolder folder : params.getWorkspaceFolders()) {
                workspaceRoots.add(Path.of(URI.create(folder.getUri())));
            }
        }
        if (workspaceRoots.isEmpty() && params.getRootUri() != null) {
            workspaceRoots.add(Path.of(URI.create(params.getRootUri())));
        }
        roots = List.copyOf(workspaceRoots);
        var workspaceCapabilities = params.getCapabilities() == null ? null : params.getCapabilities().getWorkspace();
        semanticTokensRefresh = workspaceCapabilities != null && workspaceCapabilities.getSemanticTokens() != null
                && Boolean.TRUE.equals(workspaceCapabilities.getSemanticTokens().getRefreshSupport());

        rendezvous = rendezvousDirectory(options);
        jdt = new JdtClient(rendezvous, roots,
                event -> worker.execute(this::onJavaChanged), () -> worker.execute(this::onJdtConnectionChanged));
        projects = new ProjectManager(roots, jdt, () -> worker.execute(this::republishAll));
        index = new FaceletsIndex(roots, () -> { });
        index.start();
        jdt.start();
        LOG.info("faces-ls started for " + roots + ", rendezvous " + rendezvous);

        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
        CompletionOptions completion = new CompletionOptions(false, List.of("<", ":", ".", "\"", "'", "[", "{", "/", "@", " "));
        capabilities.setCompletionProvider(completion);
        capabilities.setHoverProvider(true);
        capabilities.setDefinitionProvider(true);
        capabilities.setCallHierarchyProvider(true);
        capabilities.setReferencesProvider(true);
        capabilities.setCodeLensProvider(new CodeLensOptions(false));
        capabilities.setSemanticTokensProvider(
                new SemanticTokensWithRegistrationOptions(SemanticTokensProvider.legend(), true, true));
        capabilities.setExecuteCommandProvider(new ExecuteCommandOptions(List.of(UsageProvider.NO_OP_COMMAND)));
        capabilities.setCodeActionProvider(new CodeActionOptions(
                List.of(CodeActionKind.QuickFix, CodeActionProvider.ADD_MISSING_NAMESPACES)));
        return CompletableFuture.completedFuture(new InitializeResult(capabilities, new ServerInfo("faces-ls", "0.1.0")));
    }

    /**
     * The settings object from the initialization options, passed either as is or wrapped as
     * {@code {"settings": {...}}}.
     */
    private static JsonElement settingsOf(Object initializationOptions) {
        if (initializationOptions instanceof JsonObject options) {
            return options.get("settings") instanceof JsonObject settings ? settings : options;
        }
        return null;
    }

    private static void configureLogging(JsonElement settings) {
        String logFile = null;
        if (settings instanceof JsonObject object && object.get("log_file") instanceof JsonElement file && file.isJsonPrimitive()) {
            logFile = file.getAsString();
        }
        Logging.configure(logFile);
    }

    /**
     * Where faces-jdt announces itself: the {@code jdt_rendezvous} setting, the {@code FACES_JDT_RENDEZVOUS}
     * environment variable (set by the editor extension that loads the bundle into jdtls), or, in the standalone
     * distribution where both jars sit in {@code lib/}, the directory faces-jdt uses next to its jar.
     */
    static Path rendezvousDirectory(JsonElement settings) {
        if (settings instanceof JsonObject object && object.get("jdt_rendezvous") instanceof JsonElement value
                && value.isJsonPrimitive() && !value.getAsString().isBlank()) {
            return Path.of(value.getAsString());
        }
        String environment = System.getenv("FACES_JDT_RENDEZVOUS");
        if (environment != null && !environment.isBlank()) {
            return Path.of(environment);
        }
        try {
            Path jar = Path.of(FacesLanguageServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = jar.getParent();
            if (dir != null && Files.isRegularFile(dir.resolve("faces-jdt.jar"))) {
                return dir.resolve("faces-jdt-rendezvous");
            }
        } catch (URISyntaxException | RuntimeException e) {
            LOG.log(Level.FINE, "cannot locate faces-ls.jar", e);
        }
        return null;
    }

    @Override
    public void initialized(InitializedParams params) {
        worker.execute(this::publishJavaStatus);
        List<FileSystemWatcher> watchers = new ArrayList<>();
        for (String pattern : List.of("**/*.xhtml", "**/faces-config.xml", "**/web.xml", "**/*.taglib.xml", "**/*.properties")) {
            watchers.add(new FileSystemWatcher(Either.forLeft(pattern)));
        }
        Registration registration = new Registration("faces-ls-watchers", "workspace/didChangeWatchedFiles",
                new DidChangeWatchedFilesRegistrationOptions(watchers));
        client.registerCapability(new RegistrationParams(List.of(registration))).exceptionally(error -> {
            LOG.log(Level.FINE, "client does not support file watchers", error);
            return null;
        });
        worker.schedule(() -> requests.execute("jdtls check", this::offerToStartJdtls), 12, TimeUnit.SECONDS);
    }

    /**
     * Some editors start jdtls only for Java buffers. When a Facelets file is opened first and no jdtls has
     * announced itself, ask once whether to open a Java source of the project, which starts jdtls and with it the
     * faces-jdt bundle.
     */
    private void offerToStartJdtls() {
        if (jdt.connected() || documents.isEmpty() || rendezvous == null) {
            return;
        }
        Path javaFile = null;
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root, 12)) {
                javaFile = files.filter(FacesLanguageServer::isMainJavaSource).findFirst().orElse(null);
            } catch (IOException | UncheckedIOException e) {
                LOG.log(Level.FINE, "cannot scan " + root, e);
            }
            if (javaFile != null) {
                break;
            }
        }
        if (javaFile == null) {
            return;
        }
        Path target = javaFile;
        String action = "Open " + target.getFileName();
        ShowMessageRequestParams request = new ShowMessageRequestParams(List.of(new MessageActionItem(action)));
        request.setType(MessageType.Info);
        request.setMessage("Faces: EL type information (beans, properties, navigation to Java) needs the Java language "
                + "server (jdtls with the faces-jdt bundle), which usually starts once a Java file is open.");
        client.showMessageRequest(request).thenAccept(choice -> {
            if (choice != null && action.equals(choice.getTitle())) {
                ShowDocumentParams show = new ShowDocumentParams(target.toUri().toString());
                show.setTakeFocus(false);
                client.showDocument(show);
            }
        });
    }

    /** A {@code .java} file below a {@code src/main/java} directory. */
    static boolean isMainJavaSource(Path file) {
        Path name = file.getFileName();
        if (name == null || !name.toString().endsWith(".java")) {
            return false;
        }
        Path sources = Path.of("src", "main", "java");
        for (Path dir = file.getParent(); dir != null; dir = dir.getParent()) {
            if (dir.endsWith(sources)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        if (jdt != null) {
            jdt.close();
        }
        if (index != null) {
            index.close();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        worker.shutdownNow();
        requests.close();
        System.exit(0);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return this;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return this;
    }

    // ---------------------------------------------------------------- documents

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        var item = params.getTextDocument();
        update(item.getUri(), item.getText(), item.getVersion());
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        if (params.getContentChanges().isEmpty()) {
            return;
        }
        String text = params.getContentChanges().get(params.getContentChanges().size() - 1).getText();
        update(params.getTextDocument().getUri(), text, params.getTextDocument().getVersion());
    }

    /**
     * Makes the new text current before the next message is read, so that requests sent after the change see it;
     * the index update and the diagnostics follow on the worker.
     */
    private void update(String uri, String text, int version) {
        FacesDocument document;
        try {
            document = new FacesDocument(uri, text, version);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "cannot parse " + uri, e);
            return;
        }
        documents.put(uri, document);
        worker.execute(() -> {
            index.documentChanged(uri, text, document.xml());
            scheduleDiagnostics(uri, 250);
        });
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        ScheduledFuture<?> pending = pendingDiagnostics.remove(uri);
        if (pending != null) {
            pending.cancel(false);
        }
        synchronized (publishLock) {
            documents.remove(uri);
            diagnosticsRuns.remove(uri);
            client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
        }
        worker.execute(() -> index.documentClosed(uri));
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        FacesDocument document = documents.get(params.getTextDocument().getUri());
        if (document == null) {
            return;
        }
        requests.execute("composite update", () -> {
            if (document.compositeInterface() != null) {
                projects.forFile(document.path()).taglibs().invalidateComposites();
                republishAll();
            }
        });
    }

    private ElAnalyzer analyzer(FacesDocument document, FacesSettings current) {
        ProjectModel project = projects.forFile(document.path());
        index.addClasspath(project.classpath());
        return new ElAnalyzer(project, document).withIndex(index.snapshot()).withImports(current.imports());
    }

    private void scheduleDiagnostics(String uri, long delayMillis) {
        pendingDiagnostics.compute(uri, (key, previous) -> {
            if (previous != null) {
                previous.cancel(false);
            }
            return worker.schedule(() -> requests.execute("diagnostics of " + uri, () -> publishDiagnostics(uri)),
                    delayMillis, TimeUnit.MILLISECONDS);
        });
    }

    /**
     * Computes and publishes the diagnostics of an open document, unless it changed or closed meanwhile or a later
     * run started (which publishes instead).
     */
    private void publishDiagnostics(String uri) {
        long run = diagnosticsSequence.incrementAndGet();
        diagnosticsRuns.put(uri, run);
        FacesDocument document = documents.get(uri);
        if (document == null) {
            diagnosticsRuns.remove(uri, run);
            return;
        }
        PublishDiagnosticsParams params = new PublishDiagnosticsParams(uri,
                new DiagnosticsProvider(analyzer(document, settings)).diagnostics());
        params.setVersion(document.version());
        synchronized (publishLock) {
            if (documents.get(uri) == document && Long.valueOf(run).equals(diagnosticsRuns.get(uri))) {
                client.publishDiagnostics(params);
            }
        }
    }

    private void republishAll() {
        for (String uri : documents.keySet()) {
            scheduleDiagnostics(uri, 50);
        }
        scheduleSemanticTokensRefresh();
    }

    /** Asks the client to request semantic tokens again, once beans, types or taglibs have changed. */
    private synchronized void scheduleSemanticTokensRefresh() {
        if (!semanticTokensRefresh || documents.isEmpty()) {
            return;
        }
        if (pendingRefresh != null) {
            pendingRefresh.cancel(false);
        }
        pendingRefresh = worker.schedule(() -> {
            client.refreshSemanticTokens().exceptionally(error -> {
                LOG.log(Level.FINE, "semantic token refresh failed", error);
                return null;
            });
        }, 100, TimeUnit.MILLISECONDS);
    }

    private void onJavaChanged() {
        // the first changes after connecting usually come from jdtls importing the projects
        if (projects.projects().stream().anyMatch(project -> !project.javaModelLoaded())) {
            projects.reset();
        } else {
            projects.invalidateJava();
        }
        republishAll();
    }

    private void onJdtConnectionChanged() {
        projects.reset();
        republishAll();
        publishJavaStatus();
    }

    private void publishJavaStatus() {
        if (client instanceof FacesLanguageClient faces) {
            JsonObject status = new JsonObject();
            status.addProperty("connected", jdt != null && jdt.connected());
            faces.javaStatus(status);
        }
    }

    /** A request on a Facelets document that is open, answered with {@code fallback} for any other document. */
    private <T> CompletableFuture<T> onDocument(String name, String uri, T fallback,
                                                DocumentRequest<T> request) {
        return requests.compute(name, cancel -> {
            FacesDocument document = documents.get(uri);
            if (document == null) {
                return fallback;
            }
            FacesSettings current = settings;
            ElAnalyzer analyzer = analyzer(document, current);
            cancel.checkCanceled();
            return request.answer(document, analyzer, current);
        }, fallback);
    }

    @FunctionalInterface
    private interface DocumentRequest<T> {
        T answer(FacesDocument document, ElAnalyzer analyzer, FacesSettings settings);
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        return onDocument("completion", params.getTextDocument().getUri(), Either.forRight(new CompletionList(false, List.of())),
                (document, analyzer, current) -> {
                    int offset = document.lines().offset(params.getPosition());
                    CompletionProvider provider = new CompletionProvider(analyzer, current);
                    List<CompletionItem> items = provider.complete(offset);
                    LOG.fine(() -> "completion at " + params.getPosition().getLine() + ":"
                            + params.getPosition().getCharacter() + " -> " + items.size() + " items");
                    return Either.forRight(new CompletionList(provider.incomplete(), items));
                });
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return onDocument("hover", params.getTextDocument().getUri(), null, (document, analyzer, current) -> {
            LOG.fine(() -> "hover at " + params.getPosition().getLine() + ":" + params.getPosition().getCharacter());
            return new HoverProvider(analyzer).hover(document.lines().offset(params.getPosition()));
        });
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        return onDocument("definition", params.getTextDocument().getUri(), Either.forLeft(List.<Location>of()),
                (document, analyzer, current) -> {
                    DefinitionProvider provider = new DefinitionProvider(analyzer, other -> {
                        FacesDocument open = documents.get(other);
                        return open == null ? null : open.text();
                    });
                    List<Location> locations = provider.definition(document.lines().offset(params.getPosition()));
                    LOG.fine(() -> "definition at " + params.getPosition().getLine() + ":"
                            + params.getPosition().getCharacter() + " -> " + locations);
                    return Either.forLeft(locations);
                });
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        return onDocument("semantic tokens", params.getTextDocument().getUri(), new SemanticTokens(List.of()),
                (document, analyzer, current) -> new SemanticTokensProvider(analyzer).full());
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensRange(SemanticTokensRangeParams params) {
        return onDocument("semantic tokens", params.getTextDocument().getUri(), new SemanticTokens(List.of()),
                (document, analyzer, current) -> new SemanticTokensProvider(analyzer).range(
                        document.lines().offset(params.getRange().getStart()),
                        document.lines().offset(params.getRange().getEnd())));
    }

    // ---------------------------------------------------------------- workspace

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        return onDocument("code actions", params.getTextDocument().getUri(), List.of(), (document, analyzer, current) -> {
            int start = document.lines().offset(params.getRange().getStart());
            int end = document.lines().offset(params.getRange().getEnd());
            List<Either<Command, CodeAction>> result = new ArrayList<>();
            for (CodeAction action : new CodeActionProvider(analyzer, current)
                    .actions(start, end, params.getContext().getDiagnostics(), params.getContext().getOnly())) {
                result.add(Either.forRight(action));
            }
            return result;
        });
    }

    /** A request answered from the Facelets index, with {@code fallback} while it is not built yet. */
    private <T> CompletableFuture<T> onIndex(String name, T fallback, Function<FaceletsIndex.Snapshot, T> request) {
        return requests.compute(name, cancel -> {
            FaceletsIndex.Snapshot snapshot = index.snapshot();
            return snapshot == null ? fallback : request.apply(snapshot);
        }, fallback);
    }

    /** A request that jdtls answers through faces-jdt, with {@code fallback} while it is not connected. */
    private <T> CompletableFuture<T> onJava(String name, T fallback, Function<CancelChecker, T> request) {
        if (jdt == null || !jdt.connected()) {
            return CompletableFuture.completedFuture(fallback);
        }
        return requests.compute(name, request, fallback);
    }

    @Override
    public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(CallHierarchyPrepareParams params) {
        String uri = params.getTextDocument().getUri();
        if (isJava(uri)) {
            return onJava("call hierarchy", List.of(), cancel -> javaPrepare(params));
        }
        return onIndex("call hierarchy", List.of(), snapshot -> {
            FacesDocument document = documents.get(uri);
            if (document == null) {
                return List.of();
            }
            UsageProvider usages = new UsageProvider(snapshot);
            return List.of(usages.item(usages.targetAt(uri, document.lines().offset(params.getPosition()))));
        });
    }

    @Override
    public CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams params) {
        if (isJavaItem(params.getItem())) {
            return onJava("incoming calls", List.of(), cancel -> javaIncoming(params.getItem(), cancel));
        }
        return onIndex("incoming calls", List.of(), snapshot -> new UsageProvider(snapshot).incoming(itemUri(params.getItem())));
    }

    @Override
    public CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams params) {
        if (isJavaItem(params.getItem())) {
            return onJava("outgoing calls", List.of(), cancel -> javaCalls(
                    jdt.request("callHierarchy/outgoing", itemParams(params.getItem()), 60000), CallHierarchyOutgoingCall.class));
        }
        return onIndex("outgoing calls", List.of(), snapshot -> new UsageProvider(snapshot).outgoing(itemUri(params.getItem())));
    }

    // ---------------------------------------------------------------- call hierarchy of Java members

    private static boolean isJava(String uri) {
        return uri.endsWith(".java") || uri.startsWith("jdt:");
    }

    /**
     * Items made by faces-jdt: Java elements, which carry the Java member in {@code data.member} when faces-jdt found
     * it. Facelets items carry their URI as a string in {@code data}.
     */
    private static boolean isJavaItem(CallHierarchyItem item) {
        return isJava(item.getUri()) || item.getData() instanceof JsonObject data && data.has("member");
    }

    /** jdtls' items at the position, through faces-jdt. */
    private List<CallHierarchyItem> javaPrepare(CallHierarchyPrepareParams params) {
        JsonObject request = new JsonObject();
        request.addProperty("uri", params.getTextDocument().getUri());
        request.addProperty("line", params.getPosition().getLine());
        request.addProperty("character", params.getPosition().getCharacter());
        return javaCalls(jdt.request("callHierarchy/prepare", request, 15000), CallHierarchyItem.class);
    }

    /** jdtls' callers of a Java member, followed by the Facelets files whose EL uses it. */
    private List<CallHierarchyIncomingCall> javaIncoming(CallHierarchyItem item, CancelChecker cancel) {
        List<CallHierarchyIncomingCall> calls = new ArrayList<>(
                javaCalls(jdt.request("callHierarchy/incoming", itemParams(item), 60000), CallHierarchyIncomingCall.class));
        cancel.checkCanceled();
        JsonElement memberJson = item.getData() instanceof JsonObject data ? data.get("member") : null;
        JavaUsages.Member member = memberJson instanceof JsonObject json ? JavaUsages.Member.from(json) : null;
        Map<String, List<Range>> usages = member == null ? null : faceletsUsages(member, cancel);
        if (usages == null) {
            return calls;
        }
        UsageProvider files = new UsageProvider(index.snapshot());
        for (Map.Entry<String, List<Range>> usage : usages.entrySet()) {
            CallHierarchyItem from = files.item(usage.getKey());
            from.setRange(usage.getValue().get(0));
            from.setSelectionRange(usage.getValue().get(0));
            calls.add(new CallHierarchyIncomingCall(from, usage.getValue()));
        }
        return calls;
    }

    /**
     * The Facelets usages of the Java member at a position of a Java file. jdtls answers the Java references itself;
     * the editor shows the answers of both.
     */
    private List<Location> javaReferences(ReferenceParams params, CancelChecker cancel) {
        JsonObject request = new JsonObject();
        request.addProperty("uri", params.getTextDocument().getUri());
        request.addProperty("line", params.getPosition().getLine());
        request.addProperty("character", params.getPosition().getCharacter());
        JsonElement memberJson = jdt.request("member", request, 15000);
        if (!(memberJson instanceof JsonObject json)) {
            return List.of();
        }
        Map<String, List<Range>> usages = faceletsUsages(JavaUsages.Member.from(json), cancel);
        if (usages == null) {
            return List.of();
        }
        List<Location> locations = new ArrayList<>();
        usages.forEach((uri, ranges) -> ranges.forEach(range -> locations.add(new Location(uri, range))));
        return locations;
    }

    /** The ranges in each Facelets file whose EL uses {@code member}; null while the index is not built. */
    private Map<String, List<Range>> faceletsUsages(JavaUsages.Member member, CancelChecker cancel) {
        FaceletsIndex.Snapshot snapshot = index.snapshot();
        if (snapshot == null) {
            return null;
        }
        List<TagLibrary> libraries = new ArrayList<>();
        for (ProjectModel project : projects.projects()) {
            libraries.addAll(project.taglibs().projectLibraries());
        }
        Map<ProjectModel, Boolean> ready = new IdentityHashMap<>();
        FacesSettings current = settings;
        return new JavaUsages(snapshot, entry -> {
            cancel.checkCanceled();
            Path path = Path.of(URI.create(entry.uri()));
            ProjectModel project = projects.forFile(path);
            ready.computeIfAbsent(project, p -> awaitBeans(p, cancel));
            FacesDocument open = documents.get(entry.uri());
            FacesDocument document = open != null ? open : new FacesDocument(entry.uri(), entry.text(), 0);
            return new ElAnalyzer(project, document).withIndex(snapshot).withImports(current.imports());
        }).find(member, libraries);
    }

    /** EL names resolve through beans, which jdtls searches in the background after a change. */
    private static boolean awaitBeans(ProjectModel project, CancelChecker cancel) {
        project.beans();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!project.beansLoaded() && System.nanoTime() < deadline) {
            cancel.checkCanceled();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return project.beansLoaded();
    }

    private static JsonObject itemParams(CallHierarchyItem item) {
        JsonObject params = new JsonObject();
        params.add("item", LSP_JSON.toJsonTree(item));
        return params;
    }

    private static <T> List<T> javaCalls(JsonElement response, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (response instanceof JsonArray array) {
            for (JsonElement element : array) {
                result.add(LSP_JSON.fromJson(element, type));
            }
        }
        return result;
    }

    private static String itemUri(CallHierarchyItem item) {
        return item.getData() instanceof JsonElement data && data.isJsonPrimitive() ? data.getAsString() : item.getUri();
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        String uri = params.getTextDocument().getUri();
        if (isJava(uri)) {
            return onJava("references", List.<Location>of(), cancel -> javaReferences(params, cancel));
        }
        return onIndex("references", List.of(), snapshot -> {
            FacesDocument document = documents.get(uri);
            if (document == null) {
                return List.<Location>of();
            }
            UsageProvider usages = new UsageProvider(snapshot);
            String target = usages.targetAt(uri, document.lines().offset(params.getPosition()));
            return usages.references(target, params.getContext() != null && params.getContext().isIncludeDeclaration());
        });
    }

    @Override
    public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
        String uri = params.getTextDocument().getUri();
        return onIndex("code lenses", List.of(), snapshot -> new UsageProvider(snapshot).codeLenses(uri));
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        // the usage code lens is informational
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Settings changed: clients send them under {@code "faces"}, under {@code "faces-ls"} or as is. Diagnostics and
     * semantic tokens of the open documents follow.
     */
    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        if (params.getSettings() instanceof JsonObject json) {
            JsonElement faces = json.has("faces") ? json.get("faces") : json.has("faces-ls") ? json.get("faces-ls") : json;
            settings = FacesSettings.from(faces);
            LOG.info("settings updated: namespaces " + settings.namespaces().keySet() + ", imports " + settings.imports().tags());
            republishAll();
        }
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        boolean configuration = false;
        boolean composites = false;
        for (FileEvent event : params.getChanges()) {
            String uri = event.getUri();
            Path file;
            try {
                file = Path.of(URI.create(uri));
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "not a file: " + uri, e);
                continue;
            }
            worker.execute(() -> index.fileChanged(file));
            String name = file.getFileName() == null ? "" : file.getFileName().toString();
            if (name.endsWith("faces-config.xml") || name.endsWith("web.xml") || name.endsWith(".taglib.xml")) {
                configuration = true;
            } else if (name.endsWith(".xhtml") && isCompositeComponent(file)) {
                composites = true;
            }
        }
        boolean reloadConfiguration = configuration;
        boolean invalidateComposites = composites;
        requests.execute("configuration reload", () -> {
            for (ProjectModel project : projects.projects()) {
                if (reloadConfiguration) {
                    project.reloadConfiguration();
                } else if (invalidateComposites) {
                    project.taglibs().invalidateComposites();
                }
            }
            republishAll();
        });
    }

    /** Whether the file is a composite component candidate: below {@code resources/} or {@code META-INF/resources}. */
    private static boolean isCompositeComponent(Path file) {
        WebRoots.Location location = WebRoots.locate(file);
        return location != null && location.compositeKey() != null;
    }
}
