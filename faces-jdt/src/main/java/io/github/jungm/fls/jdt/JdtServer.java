package io.github.jungm.fls.jdt;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * JSON-RPC (lsp4j) over a loopback socket, serving {@link FacesJdtService} and sending {@link FacesJdtEvents}. The
 * port and an access token are published in a rendezvous file {@code jdtls-<pid>.json}. A connection has to send
 * {@code authenticate} with the token before anything else; a wrong token or any other request first closes it.
 * Quick queries and workspace searches (beans, call hierarchy) run on separate threads, so searches never hold up
 * the type queries behind them.
 * <p>
 * {@code javaChanged} goes only to clients holding answers that a change may have outdated: answers given before
 * the change or computed while it happened. A client that only asks after a change (one that has just connected,
 * say) has nothing to throw away.
 */
final class JdtServer {
    private final Path rendezvousDir;
    private final String bundleVersion;
    private final String token;
    private final JavaQueries queries;
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private final ExecutorService connectionThreads = Executors.newCachedThreadPool(Threads.daemon("faces-jdt-connection"));
    private final ExecutorService quick = Executors.newSingleThreadExecutor(Threads.daemon("faces-jdt-query"));
    private final ExecutorService searches = Executors.newFixedThreadPool(2, Threads.daemon("faces-jdt-search"));
    private ServerSocket serverSocket;
    private Path rendezvousFile;
    private Path logFile;

    JdtServer(Path rendezvousDir, String bundleVersion, JavaQueries queries) {
        this.rendezvousDir = rendezvousDir;
        this.bundleVersion = bundleVersion;
        this.queries = queries;
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        this.token = HexFormat.of().formatHex(bytes);
    }

    void start() throws IOException {
        Files.createDirectories(rendezvousDir);
        long pid = ProcessHandle.current().pid();
        logFile = rendezvousDir.resolve("jdtls-" + pid + ".log");
        serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());

        JsonObject info = new JsonObject();
        info.addProperty("pid", pid);
        info.addProperty("port", serverSocket.getLocalPort());
        info.addProperty("token", token);
        info.addProperty("bundleVersion", bundleVersion);
        info.addProperty("started", Instant.now().toString());
        rendezvousFile = rendezvousDir.resolve("jdtls-" + pid + ".json");
        Path temp = rendezvousDir.resolve("jdtls-" + pid + ".json.tmp");
        Files.writeString(temp, info.toString(), StandardCharsets.UTF_8);
        Files.move(temp, rendezvousFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        removeStaleFiles();

        Thread acceptor = Threads.daemon("faces-jdt-acceptor").newThread(this::acceptLoop);
        acceptor.start();
        log("faces-jdt " + bundleVersion + " listening on port " + serverSocket.getLocalPort());
    }

    void close() {
        quick.shutdownNow();
        searches.shutdownNow();
        for (Connection connection : connections) {
            connection.close();
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // shutting down
        }
        connectionThreads.shutdownNow();
        try {
            if (rendezvousFile != null) {
                Files.deleteIfExists(rendezvousFile);
            }
        } catch (IOException ignored) {
            // shutting down
        }
    }

    /** The Java model has changed: marks the clients whose answers so far may be outdated. */
    void javaModelChanged() {
        for (Connection connection : connections) {
            connection.modelChanged();
        }
    }

    /** Tells the clients marked by {@link #javaModelChanged} that Java types or beans may have changed. */
    void javaChanged() {
        int notified = 0;
        for (Connection connection : connections) {
            if (connection.takeOutdated()) {
                connection.javaChanged();
                notified++;
            }
        }
        if (notified > 0) {
            log("javaChanged sent to " + notified + " client(s)");
        }
    }

    /** Deletes rendezvous files of jdtls processes that no longer run. */
    private void removeStaleFiles() {
        try (var files = Files.list(rendezvousDir)) {
            files.filter(p -> p.getFileName().toString().matches("jdtls-\\d+\\.(json|log)")).forEach(p -> {
                String name = p.getFileName().toString();
                long pid = Long.parseLong(name.substring(6, name.indexOf('.')));
                if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    return;
                }
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                new Connection(socket).start();
            } catch (IOException | RuntimeException e) {
                if (!serverSocket.isClosed()) {
                    log("accept failed: " + e);
                }
            }
        }
    }

    void log(String message) {
        if (logFile == null) {
            return;
        }
        try {
            Files.writeString(logFile, Instant.now() + " " + message + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // logging is best effort
        }
    }

    private interface Query {
        JsonElement get() throws Exception;
    }

    /** One client connection; serves the requests of {@link FacesJdtService}. */
    private final class Connection implements FacesJdtService {
        private final Socket socket;
        private volatile boolean authenticated;
        private FacesJdtEvents client;
        // guarded by this: queries being computed, whether answers were given, whether they may be outdated
        private int running;
        private boolean answered;
        private boolean outdated;

        Connection(Socket socket) {
            this.socket = socket;
        }

        void start() throws IOException {
            Launcher<FacesJdtEvents> launcher = new Launcher.Builder<FacesJdtEvents>()
                    .setLocalService(this)
                    .setRemoteInterface(FacesJdtEvents.class)
                    .setInput(socket.getInputStream())
                    .setOutput(socket.getOutputStream())
                    .setExecutorService(connectionThreads)
                    .setClassLoader(JdtServer.class.getClassLoader())
                    .create();
            client = launcher.getRemoteProxy();
            Future<Void> listening = launcher.startListening();
            connectionThreads.execute(() -> {
                try {
                    listening.get();
                } catch (InterruptedException | ExecutionException ignored) {
                    // the connection ends either way
                } finally {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // closing
                    }
                    if (connections.remove(this)) {
                        log("client disconnected");
                    }
                }
            });
        }

        @Override
        public CompletableFuture<JsonObject> authenticate(JsonObject params) {
            JsonElement given = params == null ? null : params.get("token");
            if (given == null || !given.isJsonPrimitive() || !MessageDigest.isEqual(
                    token.getBytes(StandardCharsets.UTF_8), given.getAsString().getBytes(StandardCharsets.UTF_8))) {
                log("rejected connection without valid token");
                close();
                return CompletableFuture.failedFuture(error(ResponseErrorCode.InvalidRequest, "invalid token"));
            }
            authenticated = true;
            connections.add(this);
            log("client connected");
            JsonObject result = new JsonObject();
            result.addProperty("pid", ProcessHandle.current().pid());
            result.addProperty("bundleVersion", bundleVersion);
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public CompletableFuture<JsonElement> ping(JsonObject params) {
            return run("ping", queries::ping, quick, false);
        }

        @Override
        public CompletableFuture<JsonElement> project(JsonObject params) {
            return run("project", () -> queries.project(params), quick, true);
        }

        @Override
        public CompletableFuture<JsonElement> beans(JsonObject params) {
            return run("beans", () -> queries.beans(params), searches, true);
        }

        @Override
        public CompletableFuture<JsonElement> type(JsonObject params) {
            return run("type", () -> queries.type(params), quick, true);
        }

        @Override
        public CompletableFuture<JsonElement> location(JsonObject params) {
            return run("location", () -> queries.location(params), quick, true);
        }

        @Override
        public CompletableFuture<JsonElement> javadoc(JsonObject params) {
            return run("javadoc", () -> queries.javadoc(params), quick, true);
        }

        @Override
        public CompletableFuture<JsonElement> findTypes(JsonObject params) {
            return run("findTypes", () -> queries.findTypes(params), quick, true);
        }

        @Override
        public CompletableFuture<JsonElement> callHierarchyPrepare(JsonObject params) {
            return run("callHierarchy/prepare", () -> queries.callHierarchyPrepare(params), searches, true);
        }

        @Override
        public CompletableFuture<JsonElement> callHierarchyIncoming(JsonObject params) {
            return run("callHierarchy/incoming", () -> queries.callHierarchyIncoming(params), searches, true);
        }

        @Override
        public CompletableFuture<JsonElement> callHierarchyOutgoing(JsonObject params) {
            return run("callHierarchy/outgoing", () -> queries.callHierarchyOutgoing(params), searches, true);
        }

        @Override
        public CompletableFuture<JsonElement> member(JsonObject params) {
            return run("member", () -> queries.member(params), searches, true);
        }

        /** Runs a query on {@code pool}; {@code state} marks answers that Java changes can outdate. */
        private CompletableFuture<JsonElement> run(String method, Query query, ExecutorService pool, boolean state) {
            if (!authenticated) {
                log("rejected " + method + " before authentication");
                close();
                return CompletableFuture.failedFuture(error(ResponseErrorCode.InvalidRequest, "not authenticated"));
            }
            if (state) {
                started();
            }
            return CompletableFuture.supplyAsync(() -> {
                long start = System.nanoTime();
                boolean ok = false;
                try {
                    JsonElement result = query.get();
                    ok = true;
                    long millis = (System.nanoTime() - start) / 1_000_000;
                    if (millis > 200) {
                        log(method + " took " + millis + " ms");
                    }
                    return result == null ? JsonNull.INSTANCE : result;
                } catch (QueryException e) {
                    log(method + " not answered: " + e.getMessage());
                    throw error(ResponseErrorCode.InternalError, e.getMessage());
                } catch (Exception e) {
                    StringWriter trace = new StringWriter();
                    e.printStackTrace(new PrintWriter(trace));
                    log(method + " failed" + System.lineSeparator() + trace);
                    throw error(ResponseErrorCode.InternalError, String.valueOf(e.getMessage() != null ? e.getMessage() : e));
                } finally {
                    if (state) {
                        finished(ok);
                    }
                }
            }, pool);
        }

        private synchronized void started() {
            running++;
        }

        private synchronized void finished(boolean ok) {
            running--;
            answered |= ok;
        }

        synchronized void modelChanged() {
            if (running > 0 || answered) {
                outdated = true;
                answered = false;
            }
        }

        synchronized boolean takeOutdated() {
            boolean result = outdated;
            outdated = false;
            return result;
        }

        void javaChanged() {
            if (!authenticated) {
                return;
            }
            try {
                client.javaChanged();
            } catch (RuntimeException e) {
                close();
            }
        }

        /** Shuts the streams down, so the reader sees the end of input and the connection ends. */
        void close() {
            try {
                if (!socket.isClosed()) {
                    socket.shutdownInput();
                    socket.shutdownOutput();
                }
            } catch (IOException ignored) {
                // already shut down
            }
        }
    }

    private static ResponseErrorException error(ResponseErrorCode code, String message) {
        return new ResponseErrorException(new ResponseError(code, message, null));
    }
}
