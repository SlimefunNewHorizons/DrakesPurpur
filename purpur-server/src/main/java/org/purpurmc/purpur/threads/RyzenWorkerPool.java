package org.purpurmc.purpur.threads;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * RyzenWorkerPool — Multi-core Parallel Task Dispatcher
 * Exploits multi-threaded CPU architectures (AMD Ryzen 9 / 16-32 threads)
 * to offload world compute, Slimefun energy calculations, and async entity lookups.
 * Author: Jack (JackStar6677-1) - Star Systems & DrakesCraft Labs
 */
public final class RyzenWorkerPool {
    private static final Logger LOGGER = Logger.getLogger("RyzenWorkerPool");
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);
    
    // Auto-detect high core count, keeping headroom for netty and main thread
    private static final int PARALLELISM = Math.max(4, Runtime.getRuntime().availableProcessors() - 2);
    
    private static final ForkJoinPool POOL = new ForkJoinPool(
        PARALLELISM,
        pool -> {
            ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            worker.setName("Drakes-RyzenWorker-" + THREAD_COUNTER.getAndIncrement());
            worker.setPriority(Thread.NORM_PRIORITY);
            worker.setDaemon(true);
            return worker;
        },
        (thread, throwable) -> LOGGER.severe("[RyzenWorker] Error no capturado en " + thread.getName() + ": " + throwable.getMessage()),
        true
    );

    private RyzenWorkerPool() {}

    public static ForkJoinPool getPool() {
        return POOL;
    }

    public static int getParallelism() {
        return PARALLELISM;
    }

    public static java.util.concurrent.CompletableFuture<Void> runAsync(Runnable task) {
        return java.util.concurrent.CompletableFuture.runAsync(task, POOL);
    }

    public static <T> java.util.concurrent.CompletableFuture<T> supplyAsync(java.util.function.Supplier<T> supplier) {
        return java.util.concurrent.CompletableFuture.supplyAsync(supplier, POOL);
    }
}
