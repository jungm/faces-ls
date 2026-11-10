package io.github.jungm.fls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.jungm.fls.java.JdtClient;

/** {@link JdtClient} under concurrent use. */
class JdtClientTest {
    @TempDir
    Path workspace;

    @Test
    void concurrentQueriesAreAllAnsweredWhileNotificationsArrive() throws Exception {
        AtomicInteger events = new AtomicInteger();
        try (FakeJdt jdt = new FakeJdt(workspace, workspace.resolve("rendezvous"), event -> events.incrementAndGet())) {
            jdt.type("a.Order", List.of(), List.of(), List.of()).bean("order", "a.Order");
            ExecutorService pool = Executors.newFixedThreadPool(8);
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int thread = t;
                results.add(pool.submit(() -> {
                    int answered = 0;
                    for (int i = 0; i < 200; i++) {
                        JsonObject params = new JsonObject();
                        params.addProperty("path", workspace.toString());
                        params.addProperty("fqn", "a.Order");
                        Optional<JsonElement> answer = jdt.client().query(thread % 2 == 0 ? "type" : "beans", params, 5000);
                        if (answer.isPresent() && answer.get().isJsonArray()) {
                            answered++;
                        }
                        if (thread == 0 && i % 10 == 0) {
                            jdt.javaChanged();
                        }
                    }
                    return answered;
                }));
            }
            for (Future<Integer> result : results) {
                assertEquals(200, result.get(60, TimeUnit.SECONDS));
            }
            pool.shutdown();
            for (int i = 0; i < 100 && events.get() < 20; i++) {
                Thread.sleep(20);
            }
            assertEquals(20, events.get());
            assertTrue(jdt.client().connected());
        }
    }
}
