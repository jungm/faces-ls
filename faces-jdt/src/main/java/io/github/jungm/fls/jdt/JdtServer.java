package io.github.jungm.fls.jdt;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.core.ElementChangedEvent;
import org.eclipse.jdt.core.IElementChangedListener;
import org.eclipse.jdt.core.JavaCore;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Line-delimited JSON over a loopback socket. The port and an access token are published in a rendezvous file
 * {@code jdtls-<pid>.json}; a client authenticates with {@code {"token": ...}} and then sends
 * {@code {"id": n, "method": m, "params": {...}}}. Responses carry the same id with {@code result} or
 * {@code error}. Java model changes are pushed as {@code {"event": "javaChanged"}}.
 */
final class JdtServer implements IElementChangedListener {
    private final Path rendezvousDir;
    private final String bundleVersion;
    private final String token;
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "faces-jdt-events");
        thread.setDaemon(true);
        return thread;
    });
    private final java.util.concurrent.ExecutorService searches = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "faces-jdt-search");
        thread.setDaemon(true);
        return thread;
    });
    private final JavaModelService service;
    private ServerSocket serverSocket;
    private Path rendezvousFile;
    private Path logFile;
    private ScheduledFuture<?> pendingChange;

    JdtServer(Path rendezvousDir, String bundleVersion) {
        this.rendezvousDir = rendezvousDir;
        this.bundleVersion = bundleVersion;
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        this.token = HexFormat.of().formatHex(bytes);
        this.service = new JavaModelService(rendezvousDir.resolveSibling("faces-jdt-sources"));
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

        Thread acceptor = new Thread(this::acceptLoop, "faces-jdt-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        JavaCore.addElementChangedListener(this, ElementChangedEvent.POST_CHANGE);
        log("faces-jdt " + bundleVersion + " listening on port " + serverSocket.getLocalPort());
    }

    void close() {
        JavaCore.removeElementChangedListener(this);
        scheduler.shutdownNow();
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
        try {
            if (rendezvousFile != null) {
                Files.deleteIfExists(rendezvousFile);
            }
        } catch (IOException ignored) {
            // shutting down
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
                Connection connection = new Connection(socket);
                Thread thread = new Thread(connection::run, "faces-jdt-connection");
                thread.setDaemon(true);
                thread.start();
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    log("accept failed: " + e);
                }
            }
        }
    }

    @Override
    public void elementChanged(ElementChangedEvent event) {
        synchronized (this) {
            if (pendingChange != null) {
                pendingChange.cancel(false);
            }
            pendingChange = scheduler.schedule(() -> {
                service.invalidate();
                JsonObject message = new JsonObject();
                message.addProperty("event", "javaChanged");
                for (Connection connection : connections) {
                    connection.send(message);
                }
            }, 400, TimeUnit.MILLISECONDS);
        }
    }

    void log(String message) {
        try {
            Files.writeString(logFile, Instant.now() + " " + message + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // logging is best effort
        }
    }

    private final class Connection {
        private final Socket socket;
        private Writer writer;

        Connection(Socket socket) {
            this.socket = socket;
        }

        void run() {
            try (socket;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                String hello = reader.readLine();
                if (hello == null || !token.equals(tokenOf(hello))) {
                    log("rejected connection without valid token");
                    return;
                }
                connections.add(this);
                log("client connected");
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("\"callHierarchy/")) {
                        // workspace searches: do not hold up the quick type queries behind them
                        String request = line;
                        searches.execute(() -> handle(request));
                    } else {
                        handle(line);
                    }
                }
            } catch (IOException e) {
                // client went away
            } finally {
                connections.remove(this);
                log("client disconnected");
            }
        }

        private String tokenOf(String line) {
            try {
                JsonElement token = JsonParser.parseString(line).getAsJsonObject().get("token");
                return token == null ? null : token.getAsString();
            } catch (RuntimeException e) {
                return null;
            }
        }

        private void handle(String line) {
            JsonObject response = new JsonObject();
            try {
                JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                response.add("id", request.get("id"));
                String method = request.get("method").getAsString();
                JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
                long start = System.nanoTime();
                JsonElement result = service.handle(method, params);
                long millis = (System.nanoTime() - start) / 1_000_000;
                if (millis > 200) {
                    log(method + " took " + millis + " ms");
                }
                response.add("result", result);
            } catch (Exception e) {
                StringWriter trace = new StringWriter();
                e.printStackTrace(new PrintWriter(trace));
                log("request failed: " + line + System.lineSeparator() + trace);
                response.addProperty("error", String.valueOf(e.getMessage() != null ? e.getMessage() : e));
            }
            send(response);
        }

        synchronized void send(JsonObject message) {
            if (writer == null) {
                return;
            }
            try {
                writer.write(message.toString());
                writer.write('\n');
                writer.flush();
            } catch (IOException e) {
                close();
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }
}
