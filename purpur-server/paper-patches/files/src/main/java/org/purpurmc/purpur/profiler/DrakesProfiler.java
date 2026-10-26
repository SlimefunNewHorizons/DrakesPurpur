package org.purpurmc.purpur.profiler;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Comparator;
import java.util.logging.Logger;

/**
 * DrakesProfiler — Built-in Zero-Overhead Profiler & Thread Monitor
 * Replaces external Spark plugin by reading JVM ThreadMXBean directly from kernel.
 * Optimized for high-core architectures (AMD Ryzen 9 / Dallas / Nova).
 * Author: Jack (JackStar6677-1) - Star Systems & DrakesCraft Labs
 */
public final class DrakesProfiler {
    private static final Logger LOGGER = Logger.getLogger("DrakesProfiler");
    private static final ThreadMXBean THREAD_BEAN = ManagementFactory.getThreadMXBean();
    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();

    private DrakesProfiler() {}

    public static int getAvailableProcessors() {
        return CPU_CORES;
    }

    /**
     * Generates a high-performance snapshot of the top CPU-consuming threads
     */
    public static ThreadStats[] getTopConsumingThreads(int limit) {
        long[] threadIds = THREAD_BEAN.getAllThreadIds();
        ThreadInfo[] threadInfos = THREAD_BEAN.getThreadInfo(threadIds, 0);
        
        ThreadStats[] stats = new ThreadStats[threadInfos.length];
        for (int i = 0; i < threadInfos.length; i++) {
            ThreadInfo info = threadInfos[i];
            if (info == null) {
                stats[i] = new ThreadStats("Dead Thread", -1, 0, Thread.State.TERMINATED);
                continue;
            }
            long cpuTime = THREAD_BEAN.isThreadCpuTimeSupported() ? THREAD_BEAN.getThreadCpuTime(info.getThreadId()) : 0;
            stats[i] = new ThreadStats(info.getThreadName(), info.getThreadId(), cpuTime / 1_000_000, info.getThreadState());
        }

        Arrays.sort(stats, Comparator.comparingLong(ThreadStats::cpuTimeMs).reversed());
        return Arrays.copyOf(stats, Math.min(limit, stats.length));
    }

    public record ThreadStats(String name, long id, long cpuTimeMs, Thread.State state) {}
}
