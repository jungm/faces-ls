package io.github.jungm.fls.jdt;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Thread factories of the bundle. */
final class Threads {
    private Threads() {
    }

    /** Daemon threads named {@code name-n}, so they never keep jdtls from exiting. */
    static ThreadFactory daemon(String name) {
        AtomicInteger count = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
