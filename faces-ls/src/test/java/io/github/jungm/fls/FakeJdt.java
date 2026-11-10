package io.github.jungm.fls;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.jungm.fls.java.FacesJdtEvents;
import io.github.jungm.fls.java.FacesJdtService;
import io.github.jungm.fls.java.JdtClient;
import io.github.jungm.fls.project.ProjectModel;

/**
 * A stand-in for faces-jdt: serves the JSON-RPC protocol of {@link JdtClient} with types declared by the test, so
 * EL can be typed without jdtls. Counts the requests per method and can fail the {@code type} requests of chosen
 * types or hold back answers.
 */
final class FakeJdt implements AutoCloseable {
    private static final String TOKEN = "test";

    private final Path workspace;
    private final ServerSocket server;
    private final Map<String, JsonObject> types = new ConcurrentHashMap<>();
    private final JsonArray beans = new JsonArray();
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    private final Set<String> failingTypes = ConcurrentHashMap.newKeySet();
    private final Map<String, Hold> holds = new ConcurrentHashMap<>();
    private final List<FacesJdtEvents> clients = new CopyOnWriteArrayList<>();
    private final ExecutorService threads = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "fake-jdt");
        thread.setDaemon(true);
        return thread;
    });
    private final JdtClient client;

    FakeJdt(Path workspace, Path rendezvous) throws IOException {
        this(workspace, rendezvous, event -> { });
    }

    FakeJdt(Path workspace, Path rendezvous, Consumer<String> events) throws IOException {
        this.workspace = workspace;
        this.server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Files.createDirectories(rendezvous);
        long pid = ProcessHandle.current().pid();
        JsonObject info = new JsonObject();
        info.addProperty("pid", pid);
        info.addProperty("port", server.getLocalPort());
        info.addProperty("token", TOKEN);
        Files.writeString(rendezvous.resolve("jdtls-" + pid + ".json"), info.toString());
        threads.execute(this::accept);
        client = new JdtClient(rendezvous, List.of(workspace), events, () -> { });
        client.start();
        for (int i = 0; i < 100 && !client.connected(); i++) {
            sleep();
        }
        if (!client.connected()) {
            throw new IllegalStateException("fake faces-jdt did not connect");
        }
    }

    JdtClient client() {
        return client;
    }

    /** Declares a type: {@code fields} as "name:type", "static name:type" or "enum NAME"; methods likewise. */
    FakeJdt type(String fqn, List<String> supertypes, List<String> fields, List<String> methods) {
        JsonObject type = new JsonObject();
        type.addProperty("fqn", fqn);
        type.addProperty("kind", fields.stream().anyMatch(f -> f.startsWith("enum ")) ? "enum" : "class");
        type.addProperty("source", true);
        type.add("typeParameters", new JsonArray());
        JsonArray supers = new JsonArray();
        supertypes.forEach(supers::add);
        type.add("supertypes", supers);
        JsonArray fieldArray = new JsonArray();
        for (String field : fields) {
            JsonObject f = new JsonObject();
            boolean enumConstant = field.startsWith("enum ");
            boolean isStatic = enumConstant || field.startsWith("static ");
            String rest = field.replaceFirst("^(enum|static) ", "");
            f.addProperty("name", rest.contains(":") ? rest.substring(0, rest.indexOf(':')) : rest);
            f.addProperty("type", enumConstant ? fqn : rest.substring(rest.indexOf(':') + 1));
            f.addProperty("static", isStatic);
            f.addProperty("enumConstant", enumConstant);
            fieldArray.add(f);
        }
        type.add("fields", fieldArray);
        JsonArray methodArray = new JsonArray();
        for (String method : methods) {
            // "[static ]returnType name(paramType, ...)"
            boolean isStatic = method.startsWith("static ");
            String rest = method.replaceFirst("^static ", "");
            String returnType = rest.substring(0, rest.indexOf(' '));
            String name = rest.substring(rest.indexOf(' ') + 1, rest.indexOf('('));
            String params = rest.substring(rest.indexOf('(') + 1, rest.indexOf(')')).trim();
            JsonObject m = new JsonObject();
            m.addProperty("name", name);
            JsonArray paramArray = new JsonArray();
            if (!params.isEmpty()) {
                for (String param : params.split(",")) {
                    paramArray.add(param.trim());
                }
            }
            m.add("params", paramArray);
            m.addProperty("returnType", returnType);
            m.addProperty("static", isStatic);
            m.addProperty("varargs", false);
            m.addProperty("deprecated", false);
            methodArray.add(m);
        }
        type.add("methods", methodArray);
        types.put(fqn, type);
        return this;
    }

    /** Declares a CDI bean. */
    FakeJdt bean(String name, String type) {
        JsonObject bean = new JsonObject();
        bean.addProperty("name", name);
        bean.addProperty("type", type);
        bean.addProperty("kind", "Named");
        beans.add(bean);
        return this;
    }

    /** Makes {@code type} requests for {@code fqn} fail with an error response until {@link #recover} is called. */
    FakeJdt failing(String fqn) {
        failingTypes.add(fqn);
        return this;
    }

    FakeJdt recover(String fqn) {
        failingTypes.remove(fqn);
        return this;
    }

    /** Requests whose answer waits until {@link #release}. */
    private record Hold(Predicate<JsonObject> applies, CompletableFuture<Void> gate) {
    }

    /** Holds back the answers to {@code method} requests whose parameters match, until {@link #release}. */
    FakeJdt hold(String method, Predicate<JsonObject> applies) {
        holds.put(method, new Hold(applies, new CompletableFuture<>()));
        return this;
    }

    /** Sends the held answers to {@code method} requests and answers later ones at once. */
    FakeJdt release(String method) {
        Hold hold = holds.remove(method);
        if (hold != null) {
            hold.gate().complete(null);
        }
        return this;
    }

    /** How many requests of {@code method} were received. */
    int requests(String method) {
        AtomicInteger count = requests.get(method);
        return count == null ? 0 : count.get();
    }

    /** Pushes the {@code javaChanged} notification to the connected clients. */
    void javaChanged() {
        clients.forEach(FacesJdtEvents::javaChanged);
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Launcher<FacesJdtEvents> launcher = new Launcher.Builder<FacesJdtEvents>()
                        .setLocalService(new Service())
                        .setRemoteInterface(FacesJdtEvents.class)
                        .setInput(socket.getInputStream())
                        .setOutput(socket.getOutputStream())
                        .setExecutorService(threads)
                        .create();
                clients.add(launcher.getRemoteProxy());
                launcher.startListening();
            } catch (IOException e) {
                return;
            }
        }
    }

    private final class Service implements FacesJdtService {
        private CompletableFuture<JsonElement> answer(String method, JsonObject params, JsonElement result) {
            requests.computeIfAbsent(method, key -> new AtomicInteger()).incrementAndGet();
            Hold hold = holds.get(method);
            if (hold != null && hold.applies().test(params)) {
                return hold.gate().thenApply(released -> result);
            }
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public CompletableFuture<JsonObject> authenticate(JsonObject params) {
            if (!TOKEN.equals(params.get("token").getAsString())) {
                return CompletableFuture.failedFuture(
                        new ResponseErrorException(new ResponseError(ResponseErrorCode.InvalidRequest, "invalid token", null)));
            }
            return CompletableFuture.completedFuture(new JsonObject());
        }

        @Override
        public CompletableFuture<JsonElement> ping(JsonObject params) {
            JsonObject project = new JsonObject();
            project.addProperty("location", workspace.toString());
            JsonArray projects = new JsonArray();
            projects.add(project);
            JsonObject result = new JsonObject();
            result.add("projects", projects);
            return answer("ping", params, result);
        }

        @Override
        public CompletableFuture<JsonElement> project(JsonObject params) {
            JsonObject result = new JsonObject();
            result.add("sourceFolders", new JsonArray());
            result.add("classpath", new JsonArray());
            return answer("project", params, result);
        }

        @Override
        public CompletableFuture<JsonElement> beans(JsonObject params) {
            return answer("beans", params, beans);
        }

        /** The declared type followed by its declared supertypes, as faces-jdt answers. */
        @Override
        public CompletableFuture<JsonElement> type(JsonObject params) {
            String fqn = params.get("fqn").getAsString();
            if (failingTypes.contains(fqn)) {
                requests.computeIfAbsent("type", key -> new AtomicInteger()).incrementAndGet();
                return CompletableFuture.failedFuture(
                        new ResponseErrorException(new ResponseError(ResponseErrorCode.InternalError, "busy", null)));
            }
            JsonObject root = types.get(fqn);
            if (root == null) {
                return answer("type", params, JsonNull.INSTANCE);
            }
            JsonArray result = new JsonArray();
            Set<String> seen = new HashSet<>();
            Deque<JsonObject> queue = new ArrayDeque<>(List.of(root));
            while (!queue.isEmpty()) {
                JsonObject current = queue.poll();
                if (!seen.add(current.get("fqn").getAsString())) {
                    continue;
                }
                result.add(current);
                for (JsonElement supertype : current.getAsJsonArray("supertypes")) {
                    String name = supertype.getAsString();
                    JsonObject declared = types.get(name.contains("<") ? name.substring(0, name.indexOf('<')) : name);
                    if (declared != null) {
                        queue.add(declared);
                    }
                }
            }
            return answer("type", params, result);
        }

        @Override
        public CompletableFuture<JsonElement> location(JsonObject params) {
            return answer("location", params, JsonNull.INSTANCE);
        }

        @Override
        public CompletableFuture<JsonElement> javadoc(JsonObject params) {
            return answer("javadoc", params, JsonNull.INSTANCE);
        }

        @Override
        public CompletableFuture<JsonElement> findTypes(JsonObject params) {
            return answer("findTypes", params, new JsonArray());
        }

        @Override
        public CompletableFuture<JsonElement> callHierarchyPrepare(JsonObject params) {
            return answer("callHierarchy/prepare", params, JsonNull.INSTANCE);
        }

        @Override
        public CompletableFuture<JsonElement> callHierarchyIncoming(JsonObject params) {
            return answer("callHierarchy/incoming", params, JsonNull.INSTANCE);
        }

        @Override
        public CompletableFuture<JsonElement> callHierarchyOutgoing(JsonObject params) {
            return answer("callHierarchy/outgoing", params, JsonNull.INSTANCE);
        }

        @Override
        public CompletableFuture<JsonElement> member(JsonObject params) {
            return answer("member", params, JsonNull.INSTANCE);
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits until the project's background bean search has finished, so unknown names are reported. */
    static void awaitBeans(ProjectModel project) {
        project.beans();
        for (int i = 0; i < 100 && !project.beansLoaded(); i++) {
            sleep();
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
        server.close();
        threads.shutdownNow();
    }
}
