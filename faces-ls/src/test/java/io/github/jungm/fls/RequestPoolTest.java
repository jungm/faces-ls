package io.github.jungm.fls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Cancelled requests are dropped before they start and stop at their cancel checks; failures get the fallback. */
class RequestPoolTest {
    private final RequestPool pool = new RequestPool(1);

    @AfterEach
    void close() {
        pool.close();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Waits until the single thread has run everything submitted before. */
    private void drain() throws Exception {
        assertEquals("drained", pool.compute("drain", cancel -> "drained", "fallback").get(5, TimeUnit.SECONDS));
    }

    @Test
    void cancelledRequestDoesNotStart() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> busy = pool.compute("busy", cancel -> {
            await(release);
            return "busy";
        }, "fallback");
        AtomicBoolean ran = new AtomicBoolean();
        CompletableFuture<String> queued = pool.compute("queued", cancel -> {
            ran.set(true);
            return "queued";
        }, "fallback");

        queued.cancel(true);
        release.countDown();
        assertEquals("busy", busy.get(5, TimeUnit.SECONDS));
        drain();
        assertTrue(queued.isCancelled());
        assertFalse(ran.get());
    }

    @Test
    void runningRequestStopsAtItsCancelCheck() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        CompletableFuture<String> request = pool.compute("running", cancel -> {
            started.countDown();
            await(cancelled);
            cancel.checkCanceled();
            finished.set(true);
            return "done";
        }, "fallback");

        await(started);
        request.cancel(true);
        cancelled.countDown();
        drain();
        assertTrue(request.isCancelled());
        assertFalse(finished.get());
    }

    @Test
    void failedRequestIsAnsweredWithTheFallback() throws Exception {
        CompletableFuture<String> request = pool.compute("failing", cancel -> {
            throw new IllegalStateException("broken");
        }, "fallback");
        assertEquals("fallback", request.get(5, TimeUnit.SECONDS));
    }
}
