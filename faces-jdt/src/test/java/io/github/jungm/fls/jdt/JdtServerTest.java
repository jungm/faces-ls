package io.github.jungm.fls.jdt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The transport of {@link JdtServer}: rendezvous file, authentication, method routing, notifications. */
class JdtServerTest {
    @TempDir
    Path dir;
    JdtServer server;
    final CountDownLatch releaseBeans = new CountDownLatch(1);
    final ExecutorService threads = Executors.newCachedThreadPool(Threads.daemon("test-client"));
    final List<String> events = new CopyOnWriteArrayList<>();

    /** Answers {@code type} at once and {@code beans} only after {@link #releaseBeans}; {@code location} and {@code javadoc} fail. */
    final JavaQueries queries = new JavaQueries() {
        @Override
        public JsonElement ping() {
            JsonObject result = new JsonObject();
            result.add("projects", new JsonArray());
            return result;
        }

        @Override
        public JsonElement project(JsonObject params) {
            return JsonNull.INSTANCE;
        }

        @Override
        public JsonElement beans(JsonObject params) throws InterruptedException {
            releaseBeans.await(10, TimeUnit.SECONDS);
            return new JsonArray();
        }

        @Override
        public JsonElement type(JsonObject params) {
            if (params.get("fqn").getAsString().equals("a.Missing")) {
                return JsonNull.INSTANCE;
            }
            JsonObject type = new JsonObject();
            type.add("fqn", params.get("fqn"));
            JsonArray result = new JsonArray();
            result.add(type);
            return result;
        }

        @Override
        public JsonElement location(JsonObject params) throws QueryException {
            throw new QueryException("no Java project contains " + params.get("path").getAsString());
        }

        @Override
        public JsonElement javadoc(JsonObject params) {
            throw new IllegalStateException("javadoc broke");
        }

        @Override
        public JsonElement findTypes(JsonObject params) {
            return new JsonArray();
        }

        @Override
        public JsonElement callHierarchyPrepare(JsonObject params) {
            return JsonNull.INSTANCE;
        }

        @Override
        public JsonElement callHierarchyIncoming(JsonObject params) {
            return JsonNull.INSTANCE;
        }

        @Override
        public JsonElement callHierarchyOutgoing(JsonObject params) {
            return JsonNull.INSTANCE;
        }

        @Override
        public JsonElement member(JsonObject params) {
            return JsonNull.INSTANCE;
        }
    };

    record Client(Socket socket, FacesJdtService service, Future<Void> listening) {
    }

    @BeforeEach
    void start() throws IOException {
        server = new JdtServer(dir, "1.0.0.test", queries);
        server.start();
    }

    @AfterEach
    void stop() {
        releaseBeans.countDown();
        server.close();
        threads.shutdownNow();
    }

    private JsonObject rendezvous() throws IOException {
        return JsonParser.parseString(Files.readString(dir.resolve("jdtls-" + ProcessHandle.current().pid() + ".json")))
                .getAsJsonObject();
    }

    private Client connect() throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), rendezvous().get("port").getAsInt());
        Launcher<FacesJdtService> launcher = new Launcher.Builder<FacesJdtService>()
                .setLocalService((FacesJdtEvents) () -> events.add("javaChanged"))
                .setRemoteInterface(FacesJdtService.class)
                .setInput(socket.getInputStream())
                .setOutput(socket.getOutputStream())
                .setExecutorService(threads)
                .create();
        return new Client(socket, launcher.getRemoteProxy(), launcher.startListening());
    }

    private Client authenticated() throws Exception {
        Client client = connect();
        JsonObject hello = new JsonObject();
        hello.add("token", rendezvous().get("token"));
        JsonObject answer = client.service().authenticate(hello).get(5, TimeUnit.SECONDS);
        assertEquals(ProcessHandle.current().pid(), answer.get("pid").getAsLong());
        assertEquals("1.0.0.test", answer.get("bundleVersion").getAsString());
        return client;
    }

    private static JsonObject params(String fqn) {
        JsonObject params = new JsonObject();
        params.addProperty("path", "/work/web");
        params.addProperty("fqn", fqn);
        return params;
    }

    @Test
    void rendezvousFileAnnouncesPortAndToken() throws IOException {
        JsonObject info = rendezvous();
        assertEquals(ProcessHandle.current().pid(), info.get("pid").getAsLong());
        assertTrue(info.get("port").getAsInt() > 0);
        assertEquals(48, info.get("token").getAsString().length());
        assertEquals("1.0.0.test", info.get("bundleVersion").getAsString());
    }

    @Test
    void wrongTokenClosesTheConnection() throws Exception {
        Client client = connect();
        JsonObject hello = new JsonObject();
        hello.addProperty("token", "guess");
        CompletableFuture<JsonObject> answer = client.service().authenticate(hello);
        client.listening().get(5, TimeUnit.SECONDS);
        assertFalse(answer.isDone() && !answer.isCompletedExceptionally());
    }

    @Test
    void requestsBeforeAuthenticationAreNotServed() throws Exception {
        Client client = connect();
        CompletableFuture<JsonElement> answer = client.service().type(params("a.Order"));
        client.listening().get(5, TimeUnit.SECONDS);
        assertFalse(answer.isDone() && !answer.isCompletedExceptionally());
    }

    @Test
    void answersQueries() throws Exception {
        Client client = authenticated();
        JsonElement type = client.service().type(params("a.Order")).get(5, TimeUnit.SECONDS);
        assertEquals("a.Order", type.getAsJsonArray().get(0).getAsJsonObject().get("fqn").getAsString());
        JsonElement missing = client.service().type(params("a.Missing")).get(5, TimeUnit.SECONDS);
        assertTrue(missing == null || missing.isJsonNull());
    }

    @Test
    void failuresBecomeErrorResponses() throws Exception {
        Client client = authenticated();
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> client.service().location(params("a.Order")).get(5, TimeUnit.SECONDS));
        ResponseErrorException error = assertInstanceOf(ResponseErrorException.class, failure.getCause());
        assertEquals("no Java project contains /work/web", error.getResponseError().getMessage());
        failure = assertThrows(ExecutionException.class,
                () -> client.service().javadoc(params("a.Order")).get(5, TimeUnit.SECONDS));
        assertEquals("javadoc broke", assertInstanceOf(ResponseErrorException.class, failure.getCause())
                .getResponseError().getMessage());
    }

    @Test
    void searchesDoNotBlockTypeQueries() throws Exception {
        Client client = authenticated();
        CompletableFuture<JsonElement> beans = client.service().beans(params("a.Order"));
        CompletableFuture<JsonElement> beans2 = client.service().beans(params("a.Order"));
        JsonElement type = client.service().type(params("a.Order")).get(5, TimeUnit.SECONDS);
        assertTrue(type.isJsonArray());
        assertFalse(beans.isDone());
        assertFalse(beans2.isDone());
        releaseBeans.countDown();
        assertTrue(beans.get(5, TimeUnit.SECONDS).isJsonArray());
        assertTrue(beans2.get(5, TimeUnit.SECONDS).isJsonArray());
    }

    private void awaitEvents(int count) throws InterruptedException {
        for (int i = 0; i < 100 && events.size() < count; i++) {
            Thread.sleep(20);
        }
        Thread.sleep(100);
    }

    @Test
    void javaChangedReachesClientsHoldingAnswers() throws Exception {
        Client client = authenticated();
        Client stranger = connect();
        client.service().type(params("a.Order")).get(5, TimeUnit.SECONDS);
        server.javaModelChanged();
        server.javaChanged();
        awaitEvents(1);
        assertEquals(List.of("javaChanged"), events);

        // nothing asked since: nothing to throw away
        server.javaModelChanged();
        server.javaChanged();
        awaitEvents(2);
        assertEquals(1, events.size());
        client.socket().close();
        stranger.socket().close();
    }

    @Test
    void clientsAskingOnlyAfterAChangeAreNotNotified() throws Exception {
        Client client = authenticated();
        client.service().ping(new JsonObject()).get(5, TimeUnit.SECONDS);
        server.javaModelChanged();
        client.service().type(params("a.Order")).get(5, TimeUnit.SECONDS);
        server.javaChanged();
        awaitEvents(1);
        assertTrue(events.isEmpty());
    }

    @Test
    void answersComputedDuringAChangeAreOutdated() throws Exception {
        Client client = authenticated();
        CompletableFuture<JsonElement> beans = client.service().beans(params("a.Order"));
        Thread.sleep(100);
        server.javaModelChanged();
        releaseBeans.countDown();
        beans.get(5, TimeUnit.SECONDS);
        server.javaChanged();
        awaitEvents(1);
        assertEquals(List.of("javaChanged"), events);
    }

    @Test
    void failedQueriesLeaveNothingToThrowAway() throws Exception {
        Client client = authenticated();
        assertThrows(ExecutionException.class, () -> client.service().location(params("a.Order")).get(5, TimeUnit.SECONDS));
        server.javaModelChanged();
        server.javaChanged();
        awaitEvents(1);
        assertTrue(events.isEmpty());
    }
}
