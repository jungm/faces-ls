package io.github.jungm.fls.java;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Connects to the faces-jdt bundle running inside jdtls. The bundle announces itself in
 * {@code $FACES_JDT_RENDEZVOUS/jdtls-<pid>.json}; when several jdtls processes run (several editor windows), the one
 * whose projects contain the workspace root wins. Reconnects in the background when jdtls (re)starts.
 */
public final class JdtClient implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(JdtClient.class.getName());

    private final Path rendezvousDir;
    private final List<Path> workspaceRoots;
    private final Consumer<String> eventListener;
    private final Runnable connectionListener;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonElement>> pending = new ConcurrentHashMap<>();
    private volatile Socket socket;
    private volatile Writer writer;
    private volatile long connectedPid = -1;
    private volatile boolean matched;
    private long lastLoggedPid = -1;
    private volatile boolean closed;
    private Thread reconnectThread;

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
        reconnectThread = new Thread(this::reconnectLoop, "faces-jdt-reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    public boolean connected() {
        return socket != null && !socket.isClosed();
    }

    private void reconnectLoop() {
        while (!closed) {
            try {
                if (!connected()) {
                    tryConnect();
                } else if (!ProcessHandle.of(connectedPid).map(ProcessHandle::isAlive).orElse(false)) {
                    disconnect();
                    connectionListener.run();
                } else if (!matched) {
                    // connected to a jdtls that does not know our projects: it may still be importing them,
                    // or another jdtls (the one the editor started for this workspace) has appeared in the meantime
                    Boolean serves = servesWorkspace(10000);
                    if (Boolean.TRUE.equals(serves)) {
                        matched = true;
                        LOG.info("jdtls pid " + connectedPid + " has imported the workspace");
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
            JsonElement ping = request("ping", new JsonObject(), timeoutMillis);
            if (!ping.isJsonObject()) {
                return null;
            }
            JsonArray projects = ping.getAsJsonObject().getAsJsonArray("projects");
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

    private boolean connect(Candidate candidate) {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), candidate.port()), 2000);
            Writer w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            JsonObject hello = new JsonObject();
            hello.addProperty("token", candidate.token());
            w.write(hello + "\n");
            w.flush();
            socket = s;
            writer = w;
            connectedPid = candidate.pid();
            Thread reader = new Thread(() -> readLoop(s), "faces-jdt-reader");
            reader.setDaemon(true);
            reader.start();
            return true;
        } catch (IOException e) {
            LOG.log(Level.FINE, "cannot connect to jdtls " + candidate.pid(), e);
            return false;
        }
    }

    private void readLoop(Socket s) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonObject message = JsonParser.parseString(line).getAsJsonObject();
                if (message.has("event")) {
                    eventListener.accept(message.get("event").getAsString());
                    continue;
                }
                CompletableFuture<JsonElement> future = pending.remove(message.get("id").getAsLong());
                if (future == null) {
                    continue;
                }
                if (message.has("error")) {
                    future.completeExceptionally(new IllegalStateException(message.get("error").getAsString()));
                } else {
                    JsonElement result = message.get("result");
                    future.complete(result == null ? JsonNull.INSTANCE : result);
                }
            }
        } catch (IOException | RuntimeException e) {
            if (socket == s) {
                LOG.log(Level.FINE, "faces-jdt connection lost", e);
            }
        } finally {
            if (socket == s) {
                disconnect();
                LOG.info("disconnected from faces-jdt");
                connectionListener.run();
            }
        }
    }

    private synchronized void disconnect() {
        Socket s = socket;
        socket = null;
        writer = null;
        connectedPid = -1;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
        }
        for (CompletableFuture<JsonElement> future : pending.values()) {
            future.completeExceptionally(new IllegalStateException("disconnected"));
        }
        pending.clear();
    }

    /** Sends a request and waits for its result. Returns {@link JsonNull} when not connected or on failure. */
    public JsonElement request(String method, JsonObject params, long timeoutMillis) {
        Writer w = writer;
        if (w == null) {
            return JsonNull.INSTANCE;
        }
        long id = ids.incrementAndGet();
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        pending.put(id, future);
        JsonObject request = new JsonObject();
        request.addProperty("id", id);
        request.addProperty("method", method);
        request.add("params", params);
        try {
            synchronized (w) {
                w.write(request + "\n");
                w.flush();
            }
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            pending.remove(id);
            LOG.fine(method + " timed out after " + timeoutMillis + " ms");
            return JsonNull.INSTANCE;
        } catch (Exception e) {
            pending.remove(id);
            LOG.log(Level.FINE, method + " failed", e);
            return JsonNull.INSTANCE;
        }
    }

    public JsonElement request(String method, JsonObject params) {
        return request(method, params, 8000);
    }

    @Override
    public void close() {
        closed = true;
        if (reconnectThread != null) {
            reconnectThread.interrupt();
        }
        disconnect();
    }
}
