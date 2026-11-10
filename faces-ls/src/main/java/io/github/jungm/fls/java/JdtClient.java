package io.github.jungm.fls.java;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jungm.fls.Threads;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Connects to the faces-jdt bundle running inside jdtls: JSON-RPC (lsp4j) over a loopback socket, with the requests
 * of {@link FacesJdtService} and the notifications of {@link FacesJdtEvents}. The bundle announces itself in
 * {@code $FACES_JDT_RENDEZVOUS/jdtls-<pid>.json} with its port and a token to authenticate with; when several jdtls
 * processes run (several editor windows), the one whose projects contain the workspace root wins. Reconnects in the
 * background when jdtls (re)starts.
 */
public final class JdtClient implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(JdtClient.class.getName());
    private static final long DEFAULT_TIMEOUT_MILLIS = 8000;

    private final Path rendezvousDir;
    private final List<Path> workspaceRoots;
    private final Consumer<String> eventListener;
    private final Runnable connectionListener;
    private final ExecutorService threads = Executors.newCachedThreadPool(Threads.daemon("faces-jdt-client"));
    private volatile Connection connection;
    private volatile boolean matched;
    private long lastLoggedPid = -1;
    private volatile boolean closed;
    private Thread reconnectThread;

    /** An authenticated connection; {@code pending} holds the requests still waiting for an answer. */
    private record Connection(Socket socket, RemoteEndpoint endpoint, long pid, Set<CompletableFuture<?>> pending) {
    }

    public JdtClient(Path rendezvousDir, List<Path> workspaceRoots, Consumer<String> eventListener, Runnable connectionListener) {
        this.rendezvousDir = rendezvousDir;
        this.workspaceRoots = workspaceRoots;
        this.eventListener = eventListener;
        this.connectionListener = connectionListener;
    }

    public void start() {
        if (rendezvousDir == null) {
            LOG.info("FACES_JDT_RENDEZVOUS not set; Java integration disabled");
            return;
        }
        reconnectThread = Threads.daemon("faces-jdt-reconnect").newThread(this::reconnectLoop);
        reconnectThread.start();
    }

    public boolean connected() {
        Connection current = connection;
        return current != null && open(current.socket());
    }

    private static boolean open(Socket socket) {
        return !socket.isClosed() && !socket.isInputShutdown();
    }

    private void reconnectLoop() {
        while (!closed) {
            try {
                Connection current = connection;
                if (current == null || !open(current.socket())) {
                    tryConnect();
                } else if (!ProcessHandle.of(current.pid()).map(ProcessHandle::isAlive).orElse(false)) {
                    disconnect();
                    connectionListener.run();
                } else if (!matched) {
                    // connected to a jdtls that does not know our projects: it may still be importing them,
                    // or another jdtls (the one the editor started for this workspace) has appeared in the meantime
                    Boolean serves = servesWorkspace(10000);
                    if (Boolean.TRUE.equals(serves)) {
                        matched = true;
                        LOG.info("jdtls pid " + current.pid() + " has imported the workspace");
                        connectionListener.run();
                    } else if (serves != null) {
                        // answered without our projects: another jdtls may have appeared meanwhile
                        disconnect();
                        tryConnect();
                    }
                    // no answer: jdtls is busy (importing, indexing); ask again later instead of reconnecting
                }
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "reconnect attempt failed", e);
            }
            try {
                Thread.sleep(connected() && matched ? 5000 : 2000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private record Candidate(long pid, int port, String token, long modified) {
    }

    private List<Candidate> candidates() {
        List<Candidate> result = new ArrayList<>();
        if (!Files.isDirectory(rendezvousDir)) {
            return result;
        }
        try (var files = Files.list(rendezvousDir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (!name.matches("jdtls-\\d+\\.json")) {
                    continue;
                }
                try {
                    JsonObject info = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                    long pid = info.get("pid").getAsLong();
                    if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                        continue;
                    }
                    result.add(new Candidate(pid, info.get("port").getAsInt(), info.get("token").getAsString(),
                            Files.getLastModifiedTime(file).toMillis()));
                } catch (IOException | RuntimeException e) {
                    LOG.log(Level.FINE, "unreadable rendezvous file " + file, e);
                }
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "cannot list " + rendezvousDir, e);
        }
        result.sort(Comparator.comparingLong(Candidate::modified).reversed());
        return result;
    }

    /** Connects to the jdtls serving the workspace, or else to the newest one. Returns quietly if none runs. */
    private void tryConnect() {
        Candidate fallback = null;
        for (Candidate candidate : candidates()) {
            if (connect(candidate)) {
                if (servesWorkspace()) {
                    matched = true;
                    if (candidate.pid() != lastLoggedPid) {
                        LOG.info("connected to faces-jdt in jdtls pid " + candidate.pid());
                        lastLoggedPid = candidate.pid();
                    } else {
                        LOG.info("jdtls pid " + candidate.pid() + " has imported the workspace");
                    }
                    connectionListener.run();
                    return;
                }
                if (fallback == null) {
                    fallback = candidate;
                }
                disconnect();
            }
        }
        if (fallback != null && connect(fallback)) {
            matched = false;
            if (fallback.pid() != lastLoggedPid) {
                LOG.info("connected to faces-jdt in jdtls pid " + fallback.pid() + " (workspace not imported there yet)");
                lastLoggedPid = fallback.pid();
                connectionListener.run();
            }
        }
    }

    private boolean servesWorkspace() {
        return Boolean.TRUE.equals(servesWorkspace(3000));
    }

    /** Whether the connected jdtls has imported a project of the workspace; null when it did not answer in time. */
    private Boolean servesWorkspace(long timeoutMillis) {
        try {
            Optional<JsonElement> ping = query("ping", new JsonObject(), timeoutMillis);
            if (ping.isEmpty() || !ping.get().isJsonObject()) {
                return null;
            }
            JsonArray projects = ping.get().getAsJsonObject().getAsJsonArray("projects");
            for (JsonElement project : projects) {
                JsonElement location = project.getAsJsonObject().get("location");
                if (location == null || location.isJsonNull()) {
                    continue;
                }
                Path projectPath = Path.of(location.getAsString());
                for (Path root : workspaceRoots) {
                    if (projectPath.startsWith(root) || root.startsWith(projectPath)) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "ping failed", e);
        }
        return false;
    }

    /** Opens a connection and authenticates with the candidate's token. */
    private boolean connect(Candidate candidate) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), candidate.port()), 2000);
            Launcher<FacesJdtService> launcher = new Launcher.Builder<FacesJdtService>()
                    .setLocalService(new Events())
                    .setRemoteInterface(FacesJdtService.class)
                    .setInput(socket.getInputStream())
                    .setOutput(socket.getOutputStream())
                    .setExecutorService(threads)
                    .create();
            Connection opened = new Connection(socket, launcher.getRemoteEndpoint(), candidate.pid(), ConcurrentHashMap.newKeySet());
            Future<Void> listening = launcher.startListening();
            threads.execute(() -> awaitEnd(opened, listening));
            JsonObject hello = new JsonObject();
            hello.addProperty("token", candidate.token());
            launcher.getRemoteProxy().authenticate(hello).get(2000, TimeUnit.MILLISECONDS);
            connection = opened;
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | ExecutionException | TimeoutException | RuntimeException e) {
            LOG.log(Level.FINE, "cannot connect to jdtls " + candidate.pid(), e);
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // not connected
        }
        return false;
    }

    /** Waits until the connection's input ends, then fails its pending requests and reports the disconnect. */
    private void awaitEnd(Connection ended, Future<Void> listening) {
        try {
            listening.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | CancellationException e) {
            if (connection == ended) {
                LOG.log(Level.FINE, "faces-jdt connection lost", e);
            }
        }
        boolean current;
        synchronized (this) {
            current = connection == ended;
            if (current) {
                connection = null;
            }
        }
        close(ended);
        try {
            ended.socket().close();
        } catch (IOException ignored) {
            // closing
        }
        if (current) {
            LOG.info("disconnected from faces-jdt");
            connectionListener.run();
        }
    }

    private synchronized void disconnect() {
        Connection current = connection;
        connection = null;
        if (current != null) {
            close(current);
        }
    }

    /**
     * Ends a connection by shutting its streams down, so the reader sees the end of input instead of a closed socket;
     * {@link #awaitEnd} closes the socket.
     */
    private static void close(Connection closing) {
        Socket socket = closing.socket();
        try {
            if (!socket.isClosed()) {
                socket.shutdownInput();
                socket.shutdownOutput();
            }
        } catch (IOException ignored) {
            // already shut down
        }
        for (CompletableFuture<?> future : closing.pending()) {
            future.completeExceptionally(new IllegalStateException("disconnected"));
        }
    }

    /**
     * Sends a request and waits for its result: the server's answer ({@link JsonNull} when it answered null), or
     * empty when there is no answer because faces-jdt is not connected, does not answer in time, fails or goes away.
     */
    public Optional<JsonElement> query(String method, JsonObject params, long timeoutMillis) {
        Connection current = connection;
        if (current == null || !open(current.socket())) {
            return Optional.empty();
        }
        CompletableFuture<Object> future;
        try {
            future = current.endpoint().request(method, params);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, method + " could not be sent", e);
            return Optional.empty();
        }
        current.pending().add(future);
        try {
            Object result = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return Optional.of(result instanceof JsonElement json ? json : JsonNull.INSTANCE);
        } catch (TimeoutException e) {
            // lsp4j sends $/cancelRequest
            future.cancel(true);
            LOG.fine(method + " timed out after " + timeoutMillis + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.completeExceptionally(e);
        } catch (ExecutionException | CancellationException e) {
            LOG.log(Level.FINE, method + " failed", e);
        } finally {
            current.pending().remove(future);
        }
        return Optional.empty();
    }

    public Optional<JsonElement> query(String method, JsonObject params) {
        return query(method, params, DEFAULT_TIMEOUT_MILLIS);
    }

    /** Sends a request and waits for its result. Returns {@link JsonNull} when not connected or on failure. */
    public JsonElement request(String method, JsonObject params, long timeoutMillis) {
        return query(method, params, timeoutMillis).orElse(JsonNull.INSTANCE);
    }

    public JsonElement request(String method, JsonObject params) {
        return request(method, params, DEFAULT_TIMEOUT_MILLIS);
    }

    @Override
    public void close() {
        closed = true;
        if (reconnectThread != null) {
            reconnectThread.interrupt();
        }
        disconnect();
        // without interrupting: the readers end with their input and close their sockets
        threads.shutdown();
    }

    /** Receives the notifications of faces-jdt. */
    private final class Events implements FacesJdtEvents {
        @Override
        public void javaChanged() {
            eventListener.accept("javaChanged");
        }
    }
}
