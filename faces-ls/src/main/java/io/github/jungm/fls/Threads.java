package io.github.jungm.fls;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Thread factories of the server. */
public final class Threads {
    private Threads() {
    }

    /** Daemon threads named {@code name-n}, so they never keep the server from exiting. */
    public static ThreadFactory daemon(String name) {
        AtomicInteger count = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
