package io.github.jungm.fls;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;

/**
 * A small pool for requests and other work that may wait on jdtls, so that one slow answer does not hold up the
 * others. A request the client cancels ({@code $/cancelRequest}) is dropped when it has not started yet, and stops at
 * the points where it checks its {@link CancelChecker}.
 */
final class RequestPool implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(RequestPool.class.getName());

    private final ExecutorService executor;

    RequestPool(int threads) {
        executor = Executors.newFixedThreadPool(threads, Threads.daemon("faces-ls-request"));
    }

    /**
     * Runs a request. A failure is logged and answered with {@code fallback}; a cancellation completes the future
     * as cancelled, which lsp4j answers with {@code RequestCancelled}.
     */
    <T> CompletableFuture<T> compute(String name, Function<CancelChecker, T> request, T fallback) {
        return CompletableFutures.computeAsync(executor, cancel -> {
            cancel.checkCanceled();
            try {
                return request.apply(cancel);
            } catch (CancellationException e) {
                throw e;
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, name + " failed", e);
                return fallback;
            }
        });
    }

    /** Runs work that answers no request, such as diagnostics; a failure is logged. */
    void execute(String name, Runnable work) {
        executor.execute(() -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, name + " failed", e);
            }
        });
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
