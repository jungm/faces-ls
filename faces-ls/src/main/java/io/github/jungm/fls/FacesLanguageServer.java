package io.github.jungm.fls;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

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
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.CodeLensParams;
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
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensRangeParams;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.ShowDocumentParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.WorkspaceFolder;
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
import io.github.jungm.fls.features.SemanticTokensProvider;
import io.github.jungm.fls.features.UsageProvider;
import io.github.jungm.fls.index.FaceletsIndex;
import io.github.jungm.fls.java.JdtClient;
import io.github.jungm.fls.project.ProjectManager;
import io.github.jungm.fls.project.ProjectModel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The faces-ls language server. All analysis runs on one thread, so the models need no locking.
 */
public final class FacesLanguageServer implements LanguageServer, LanguageClientAware, TextDocumentService, WorkspaceService {
    private static final Logger LOG = Logger.getLogger(FacesLanguageServer.class.getName());

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "faces-ls-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, FacesDocument> documents = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingDiagnostics = new ConcurrentHashMap<>();
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
            if (dir != null && java.nio.file.Files.isRegularFile(dir.resolve("faces-jdt.jar"))) {
                return dir.resolve("faces-jdt-rendezvous");
            }
        } catch (java.net.URISyntaxException | RuntimeException e) {
            LOG.log(Level.FINE, "cannot locate faces-ls.jar", e);
        }
        return null;
    }

    @Override
    public void initialized(InitializedParams params) {
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
        worker.schedule(this::offerToStartJdtls, 12, TimeUnit.SECONDS);
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
            try (var files = java.nio.file.Files.walk(root, 12)) {
                javaFile = files.filter(p -> p.toString().endsWith(".java") && p.toString().contains("/src/main/java/"))
                        .findFirst().orElse(null);
            } catch (java.io.IOException | java.io.UncheckedIOException e) {
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

    private void update(String uri, String text, int version) {
        worker.execute(() -> {
            FacesDocument document = new FacesDocument(uri, text, version);
            documents.put(uri, document);
            index.documentChanged(uri, text, document.xml());
            scheduleDiagnostics(uri, 250);
        });
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        worker.execute(() -> {
            documents.remove(uri);
            index.documentClosed(uri);
            client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
        });
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        worker.execute(() -> {
            FacesDocument document = documents.get(uri);
            if (document != null && document.compositeInterface() != null) {
                projects.forFile(document.path()).taglibs().invalidateComposites();
                republishAll();
            }
        });
    }

    private ElAnalyzer analyzer(FacesDocument document) {
        ProjectModel project = projects.forFile(document.path());
        index.addClasspath(project.classpath());
        return new ElAnalyzer(project, document).withIndex(index.snapshot());
    }

    private void scheduleDiagnostics(String uri, long delayMillis) {
        ScheduledFuture<?> previous = pendingDiagnostics.remove(uri);
        if (previous != null) {
            previous.cancel(false);
        }
        pendingDiagnostics.put(uri, worker.schedule(() -> publishDiagnostics(uri), delayMillis, TimeUnit.MILLISECONDS));
    }

    private void publishDiagnostics(String uri) {
        pendingDiagnostics.remove(uri);
        FacesDocument document = documents.get(uri);
        if (document == null) {
            return;
        }
        try {
            var diagnostics = new DiagnosticsProvider(analyzer(document)).diagnostics();
            PublishDiagnosticsParams params = new PublishDiagnosticsParams(uri, diagnostics);
            params.setVersion(document.version());
            client.publishDiagnostics(params);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "diagnostics failed for " + uri, e);
        }
    }

    private void republishAll() {
        for (String uri : documents.keySet()) {
            scheduleDiagnostics(uri, 50);
        }
        scheduleSemanticTokensRefresh();
    }

    /** Asks the client to request semantic tokens again, once beans, types or taglibs have changed. */
    private void scheduleSemanticTokensRefresh() {
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
        }, 300, TimeUnit.MILLISECONDS);
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
    }

    private <T> CompletableFuture<T> onWorker(Supplier<T> task, T fallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.get();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "request failed", e);
                return fallback;
            }
        }, worker);
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        String uri = params.getTextDocument().getUri();
        return onWorker(() -> {
            FacesDocument document = documents.get(uri);
            if (document == null) {
                return Either.forRight(new CompletionList(false, List.of()));
            }
            int offset = document.lines().offset(params.getPosition());
            CompletionProvider provider = new CompletionProvider(analyzer(document), settings);
            List<CompletionItem> items = provider.complete(offset);
            LOG.fine(() -> "completion at " + params.getPosition().getLine() + ":" + params.getPosition().getCharacter()
                    + " -> " + items.size() + " items");
            return Either.forRight(new CompletionList(provider.incomplete(), items));
        }, Either.forRight(new CompletionList(false, List.of())));
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        String uri = params.getTextDocument().getUri();
        return onWorker(() -> {
            FacesDocument document = documents.get(uri);
            if (document == null) {
                return null;
            }
            LOG.fine(() -> "hover at " + params.getPosition().getLine() + ":" + params.getPosition().getCharacter());
            return new HoverProvider(analyzer(document)).hover(document.lines().offset(params.getPosition()));
        }, null);
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        String uri = params.getTextDocument().getUri();
        return onWorker(() -> {
            FacesDocument document = documents.get(uri);
            if (document == null) {
                return Either.forLeft(List.<Location>of());
            }
            DefinitionProvider provider = new DefinitionProvider(analyzer(document), other -> {
                FacesDocument open = documents.get(other);
                return open == null ? null : open.text();
            });
            List<Location> locations = provider.definition(document.lines().offset(params.getPosition()));
            LOG.fine(() -> "definition at " + params.getPosition().getLine() + ":" + params.getPosition().getCharacter()
                    + " -> " + locations);
            return Either.forLeft(locations);
        }, Either.forLeft(List.<Location>of()));
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        String uri = params.getTextDocument().getUri();
        return onWorker(() -> {
            FacesDocument document = documents.get(uri);
            return document == null ? new SemanticTokens(List.of()) : new SemanticTokensProvider(analyzer(document)).full();
        }, new SemanticTokens(List.of()));
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensRange(SemanticTokensRangeParams params) {
        String uri = params.getTextDocument().getUri();
        return onWorker(() -> {
            FacesDocument document = documents.get(uri);
            if (document == null) {
                return new SemanticTokens(List.of());
            }
            return new SemanticTokensProvider(analyzer(document)).range(document.lines().offset(params.getRange().getStart()),
                    document.lines().offset(params.getRange().getEnd()));
        }, new SemanticTokens(List.of()));
    }

    // ---------------------------------------------------------------- workspace

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        String uri = params.getTextDocument().getUri();
        return onWorker(() -> {
            FacesDocument document = documents.get(uri);
            if (document == null) {
                return List.<Either<Command, CodeAction>>of();
            }
            int start = document.lines().offset(params.getRange().getStart());
            int end = document.lines().offset(params.getRange().getEnd());
            List<Either<Command, CodeAction>> result = new ArrayList<>();
            for (CodeAction action : new CodeActionProvider(analyzer(document), settings)
                    .actions(start, end, params.getContext().getDiagnostics(), params.getContext().getOnly())) {
                result.add(Either.forRight(action));
            }
            return result;
        }, List.of());
    }

    @Override
    public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(CallHierarchyPrepareParams params) {
        String uri = params.getTextDocument().getUri();
        return onWorker(() -> {
            FacesDocument document = documents.get(uri);
            FaceletsIndex.Snapshot snapshot = index.snapshot();
            if (document == null || snapshot == null) {
                return List.<CallHierarchyItem>of();
            }
            UsageProvider usages = new UsageProvider(snapshot);
            return List.of(usages.item(usages.targetAt(uri, document.lines().offset(params.getPosition()))));
        }, List.of());
    }

    @Override
    public CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams params) {
        return onWorker(() -> {
            FaceletsIndex.Snapshot snapshot = index.snapshot();
            return snapshot == null ? List.<CallHierarchyIncomingCall>of()
                    : new UsageProvider(snapshot).incoming(itemUri(params.getItem()));
        }, List.of());
    }

    @Override
    public CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams params) {
        return onWorker(() -> {
            FaceletsIndex.Snapshot snapshot = index.snapshot();
            return snapshot == null ? List.<CallHierarchyOutgoingCall>of()
                    : new UsageProvider(snapshot).outgoing(itemUri(params.getItem()));
        }, List.of());
    }

    private static String itemUri(CallHierarchyItem item) {
        return item.getData() instanceof JsonElement data && data.isJsonPrimitive() ? data.getAsString() : item.getUri();
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        String uri = params.getTextDocument().getUri();
        return onWorker(() -> {
            FacesDocument document = documents.get(uri);
            FaceletsIndex.Snapshot snapshot = index.snapshot();
            if (document == null || snapshot == null) {
                return List.<Location>of();
            }
            UsageProvider usages = new UsageProvider(snapshot);
            String target = usages.targetAt(uri, document.lines().offset(params.getPosition()));
            return usages.references(target, params.getContext() != null && params.getContext().isIncludeDeclaration());
        }, List.of());
    }

    @Override
    public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
        String uri = params.getTextDocument().getUri();
        return onWorker(() -> {
            FaceletsIndex.Snapshot snapshot = index.snapshot();
            return snapshot == null ? List.<CodeLens>of() : new UsageProvider(snapshot).codeLenses(uri);
        }, List.of());
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        // the usage code lens is informational
        return CompletableFuture.completedFuture(null);
    }

    /** Settings changed: clients send them under {@code "faces"}, under {@code "faces-ls"} or as is. */
    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        if (params.getSettings() instanceof JsonObject json) {
            JsonElement faces = json.has("faces") ? json.get("faces") : json.has("faces-ls") ? json.get("faces-ls") : json;
            settings = FacesSettings.from(faces);
            LOG.info("settings updated: namespaces " + settings.namespaces().keySet());
        }
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        worker.execute(() -> {
            boolean configuration = false;
            boolean composites = false;
            for (FileEvent event : params.getChanges()) {
                String uri = event.getUri();
                try {
                    index.fileChanged(Path.of(URI.create(uri)));
                } catch (RuntimeException e) {
                    LOG.log(Level.FINE, "cannot index " + uri, e);
                }
                if (uri.endsWith("faces-config.xml") || uri.endsWith("web.xml") || uri.endsWith(".taglib.xml")) {
                    configuration = true;
                } else if (uri.endsWith(".xhtml") && uri.contains("/resources/")) {
                    composites = true;
                }
            }
            for (ProjectModel project : projects.projects()) {
                if (configuration) {
                    project.reloadConfiguration();
                } else if (composites) {
                    project.taglibs().invalidateComposites();
                }
            }
            republishAll();
        });
    }
}
