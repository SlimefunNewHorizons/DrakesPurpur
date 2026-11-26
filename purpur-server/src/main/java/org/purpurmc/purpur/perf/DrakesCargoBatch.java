package org.purpurmc.purpur.perf;

import org.purpurmc.purpur.nativelib.HybridNative;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.logging.Logger;

/**
 * Batched Slimefun / Networks item-stack hashing and cargo route matching.
 * <p>
 * Processes an entire tick batch in a single Panama FFM downcall to
 * {@code drakes_cargo_batch_hash} / {@code drakes_cargo_batch_route} inside
 * {@code libdrakes_hybrid_ffi.so}, avoiding per-item FFM overhead, with an
 * identical pure-Java fallback when native execution is unavailable or below
 * {@link #MIN_NATIVE_BATCH}.
 */
public final class DrakesCargoBatch {
    private static final Logger LOG = Logger.getLogger("DrakesCargoBatch");

    public static final byte FLAG_IGNORE_AMOUNT = 1 << 0;
    public static final byte FLAG_IGNORE_META = 1 << 1;
    public static final int MIN_NATIVE_BATCH = 16;

    private static final long FNV_OFFSET = 0xcbf2_9ce4_8422_2325L;
    private static final long FNV_PRIME = 0x0100_0000_01b3L;

    private static volatile MethodHandle nativeHash;
    private static volatile MethodHandle nativeRoute;
    private static volatile boolean nativeProbed;

    private DrakesCargoBatch() {}

    private static void probeNative() {
        if (nativeProbed) return;
        synchronized (DrakesCargoBatch.class) {
            if (nativeProbed) return;
            nativeProbed = true;
            try {
                String libPath = HybridNative.libraryPath();
                if (libPath == null || libPath.isBlank()) return;
                SymbolLookup lookup = SymbolLookup.libraryLookup(Path.of(libPath), Arena.global());
                Linker linker = Linker.nativeLinker();

                var hashSym = lookup.find("drakes_cargo_batch_hash");
                if (hashSym.isPresent()) {
                    nativeHash = linker.downcallHandle(
                        hashSym.get(),
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS
                        )
                    );
                }

                var routeSym = lookup.find("drakes_cargo_batch_route");
                if (routeSym.isPresent()) {
                    nativeRoute = linker.downcallHandle(
                        routeSym.get(),
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT
                        )
                    );
                }
                if (nativeHash != null && nativeRoute != null) {
                    LOG.info("[DrakesCargoBatch] Rust FFM cargo batch kernels active (" + libPath + ")");
                }
            } catch (Throwable t) {
                LOG.warning("[DrakesCargoBatch] Pure-Java fallback (Rust symbols unavailable): " + t.getMessage());
                nativeHash = null;
                nativeRoute = null;
            }
        }
    }

    /**
     * Computes deterministic 64-bit composite keys for {@code materialIds.length} item stacks
     * in a single batched pass.
     */
    public static int hashBatch(int[] materialIds, int[] amounts, long[] nbtHashes, byte[] flags, long[] outHashes) {
        int n = Math.min(Math.min(materialIds.length, amounts.length),
            Math.min(Math.min(nbtHashes.length, flags.length), outHashes.length));
        if (n <= 0) return 0;

        probeNative();
        MethodHandle handle = nativeHash;
        if (handle != null && n >= MIN_NATIVE_BATCH) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment sMats = arena.allocateFrom(ValueLayout.JAVA_INT, Arrays.copyOf(materialIds, n));
                MemorySegment sAmts = arena.allocateFrom(ValueLayout.JAVA_INT, Arrays.copyOf(amounts, n));
                MemorySegment sNbts = arena.allocateFrom(ValueLayout.JAVA_LONG, Arrays.copyOf(nbtHashes, n));
                MemorySegment sFlags = arena.allocateFrom(ValueLayout.JAVA_BYTE, Arrays.copyOf(flags, n));
                MemorySegment sOut = arena.allocate(ValueLayout.JAVA_LONG, n);

                int rc = (int) handle.invokeExact(sMats, sAmts, sNbts, sFlags, n, sOut);
                if (rc >= 0) {
                    MemorySegment.copy(sOut, ValueLayout.JAVA_LONG, 0, outHashes, 0, rc);
                    return rc;
                }
            } catch (Throwable t) {
                LOG.warning("[DrakesCargoBatch] Rust hash FFI error, falling back to Java: " + t.getMessage());
                nativeHash = null;
            }
        }
        return hashBatchJava(materialIds, amounts, nbtHashes, flags, outHashes);
    }

    /** Pure-Java reference implementation with identical semantics to {@code cargo_batch::batch_hash_items}. */
    public static int hashBatchJava(int[] materialIds, int[] amounts, long[] nbtHashes, byte[] flags, long[] outHashes) {
        int n = Math.min(Math.min(materialIds.length, amounts.length),
            Math.min(Math.min(nbtHashes.length, flags.length), outHashes.length));
        for (int i = 0; i < n; i++) {
            byte f = flags[i];
            long h = mixU64(FNV_OFFSET, Integer.toUnsignedLong(materialIds[i]));
            if ((f & FLAG_IGNORE_META) == 0) {
                h = mixU64(h, nbtHashes[i]);
            }
            if ((f & FLAG_IGNORE_AMOUNT) == 0) {
                h = mixU64(h, Math.max(0, amounts[i]));
            }
            outHashes[i] = h;
        }
        return n;
    }

    private static long mixU64(long h, long v) {
        h ^= v;
        h *= FNV_PRIME;
        h ^= (h >>> 33);
        h *= 0xff51_afd7_ed55_8ccdL;
        return h ^ (h >>> 33);
    }

    /**
     * Matches source item stacks against destination filters ordered by priority descending
     * in a single batched pass. Writes matched triples into {@code outSrc}, {@code outDst},
     * {@code outAmt} and returns the number of routes written.
     */
    public static int routeBatch(
        long[] srcKeys, int[] srcAmounts,
        long[] dstKeys, int[] dstCaps, int[] dstPriorities,
        int[] outSrc, int[] outDst, int[] outAmt
    ) {
        int ns = Math.min(srcKeys.length, srcAmounts.length);
        int nd = Math.min(dstKeys.length, Math.min(dstCaps.length, dstPriorities.length));
        int cap = Math.min(outSrc.length, Math.min(outDst.length, outAmt.length));
        if (ns <= 0 || nd <= 0 || cap <= 0) return 0;

        probeNative();
        MethodHandle handle = nativeRoute;
        if (handle != null && (ns + nd) >= MIN_NATIVE_BATCH) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment sSrcKeys = arena.allocateFrom(ValueLayout.JAVA_LONG, Arrays.copyOf(srcKeys, ns));
                MemorySegment sSrcAmts = arena.allocateFrom(ValueLayout.JAVA_INT, Arrays.copyOf(srcAmounts, ns));
                MemorySegment sDstKeys = arena.allocateFrom(ValueLayout.JAVA_LONG, Arrays.copyOf(dstKeys, nd));
                MemorySegment sDstCaps = arena.allocateFrom(ValueLayout.JAVA_INT, Arrays.copyOf(dstCaps, nd));
                MemorySegment sDstPrio = arena.allocateFrom(ValueLayout.JAVA_INT, Arrays.copyOf(dstPriorities, nd));
                MemorySegment sOutSrc = arena.allocate(ValueLayout.JAVA_INT, cap);
                MemorySegment sOutDst = arena.allocate(ValueLayout.JAVA_INT, cap);
                MemorySegment sOutAmt = arena.allocate(ValueLayout.JAVA_INT, cap);

                int rc = (int) handle.invokeExact(
                    sSrcKeys, sSrcAmts, ns,
                    sDstKeys, sDstCaps, sDstPrio, nd,
                    sOutSrc, sOutDst, sOutAmt, cap
                );
                if (rc >= 0) {
                    MemorySegment.copy(sOutSrc, ValueLayout.JAVA_INT, 0, outSrc, 0, rc);
                    MemorySegment.copy(sOutDst, ValueLayout.JAVA_INT, 0, outDst, 0, rc);
                    MemorySegment.copy(sOutAmt, ValueLayout.JAVA_INT, 0, outAmt, 0, rc);
                    return rc;
                }
            } catch (Throwable t) {
                LOG.warning("[DrakesCargoBatch] Rust route FFI error, falling back to Java: " + t.getMessage());
                nativeRoute = null;
            }
        }
        return routeBatchJava(srcKeys, srcAmounts, dstKeys, dstCaps, dstPriorities, outSrc, outDst, outAmt);
    }

    /** Pure-Java reference implementation with identical semantics to {@code cargo_batch::batch_match_cargo_routes}. */
    public static int routeBatchJava(
        long[] srcKeys, int[] srcAmounts,
        long[] dstKeys, int[] dstCaps, int[] dstPriorities,
        int[] outSrc, int[] outDst, int[] outAmt
    ) {
        int ns = Math.min(srcKeys.length, srcAmounts.length);
        int nd = Math.min(dstKeys.length, Math.min(dstCaps.length, dstPriorities.length));
        int cap = Math.min(outSrc.length, Math.min(outDst.length, outAmt.length));
        if (ns <= 0 || nd <= 0 || cap <= 0) return 0;

        Integer[] order = new Integer[nd];
        for (int i = 0; i < nd; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            int cmp = Integer.compare(dstPriorities[b], dstPriorities[a]);
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });

        int[] remSrc = new int[ns];
        for (int i = 0; i < ns; i++) remSrc[i] = Math.max(0, srcAmounts[i]);
        int[] remDst = new int[nd];
        for (int i = 0; i < nd; i++) remDst[i] = Math.max(0, dstCaps[i]);

        int written = 0;
        outer:
        for (int si = 0; si < ns; si++) {
            int avail = remSrc[si];
            if (avail <= 0) continue;
            long key = srcKeys[si];
            for (int di : order) {
                if (dstKeys[di] != key) continue;
                int room = remDst[di];
                if (room <= 0) continue;
                int moved = Math.min(avail, room);
                outSrc[written] = si;
                outDst[written] = di;
                outAmt[written] = moved;
                written++;

                avail -= moved;
                remSrc[si] = avail;
                remDst[di] = room - moved;

                if (written >= cap) break outer;
                if (avail <= 0) break;
            }
        }
        return written;
    }
}
