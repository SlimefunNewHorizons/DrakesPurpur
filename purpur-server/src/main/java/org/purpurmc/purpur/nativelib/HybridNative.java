package org.purpurmc.purpur.nativelib;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * DrakesPurpur Horizon Hybrid Engine: puente Panama (FFM, sin JNI) a {@code libdrakes_hybrid_ffi} (ABI 1).
 * <p>
 * Reglas: Java sigue siendo la unica autoridad del mundo; Rust solo calcula sobre buffers que Java le presta
 * durante la llamada. Cada operacion tiene una implementacion Java de referencia con la MISMA semantica y se usa
 * cuando: el motor esta apagado (por defecto), falta la biblioteca, la ABI no coincide, el tamano del lote es menor
 * que el umbral, o una llamada nativa falla (esa ruta se apaga hasta el reinicio). Ver docs/hybrid/.
 * <p>
 * Seguridad de hilos: las operaciones son puras y sin estado, se pueden llamar desde cualquier hilo.
 */
public final class HybridNative {
    private static final Logger LOG = Logger.getLogger("HybridNative");
    public static final int ABI = 1;

    public record Settings(boolean enabled, String dir, boolean bits, boolean noise, boolean region,
                           int minBatch) {
        public static Settings off() {
            return new Settings(false, "natives", false, false, false, 2048);
        }
    }

    private static volatile boolean bitsOn, noiseOn, regionOn;
    private static volatile int minBatch = 2048;
    private static volatile Natives natives;
    private static volatile boolean initialized;
    private static volatile String libraryPath;
    private static volatile int libraryAbi = -1;
    private static final java.util.concurrent.atomic.AtomicLong FAILURES = new java.util.concurrent.atomic.AtomicLong();
    private static volatile String lastError = "";

    private HybridNative() {}

    public static synchronized void init(Settings s) {
        if (initialized) return;
        initialized = true;
        if (!s.enabled()) {
            LOG.info("[Hybrid] apagado (native.hybrid.enabled=false): todo corre en Java.");
            return;
        }
        if (Runtime.version().feature() < 22) {
            LOG.warning("[Hybrid] requiere Java 22+ (FFM); se usa Java.");
            return;
        }
        Path lib = find(s.dir());
        if (lib == null) {
            LOG.warning("[Hybrid] libdrakes_hybrid_ffi no encontrada; se usa Java.");
            return;
        }
        try {
            Natives n = new Natives(lib);
            int abi = n.abi();
            if (abi != ABI) {
                LOG.warning("[Hybrid] " + lib + " ABI " + abi + " != " + ABI + "; se usa Java.");
                return;
            }
            natives = n;
            libraryPath = lib.toString();
            libraryAbi = abi;
            minBatch = Math.max(1, s.minBatch());
            bitsOn = s.bits();
            noiseOn = s.noise();
            regionOn = s.region();
            LOG.info("[Hybrid] " + status() + " lib=" + lib);
        } catch (Throwable t) {
            LOG.warning("[Hybrid] no se pudo enlazar (" + t + "); se usa Java.");
        }
    }

    /** Solo auto-test/benchmarks dentro del servidor: conmuta la ruta de bits si la biblioteca esta cargada. */
    public static boolean forceBits(boolean on) {
        if (natives == null) return false;
        bitsOn = on;
        return true;
    }

    public static boolean libraryLoaded() {
        return natives != null;
    }

    /** Solo para tests/benchmarks: permite re-inicializar. */
    public static synchronized void resetForTests() {
        initialized = false;
        bitsOn = noiseOn = regionOn = false;
        natives = null;
    }

    public static String status() {
        return "bits=" + bitsOn + " noise=" + noiseOn + " region=" + regionOn + " minBatch=" + minBatch;
    }

    private static Path find(String dir) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String base = "drakes_hybrid_ffi";
        String file = os.contains("win") ? base + ".dll" : os.contains("mac") ? "lib" + base + ".dylib" : "lib" + base + ".so";
        for (String d : new String[] {System.getProperty("drakes.native.dir"), System.getenv("DRAKES_NATIVE_DIR"), dir,
            "drakes-data/natives", "natives"}) {
            if (d == null || d.isBlank()) continue;
            Path p = Path.of(d, file);
            if (Files.isRegularFile(p)) return p.toAbsolutePath();
        }
        return null;
    }

    // ---- API publica (siempre funciona; nativo solo si esta activo y el lote lo justifica) ----------------

    /** Empaqueta valores al formato SimpleBitStorage de vanilla. Devuelve los longs necesarios ({@code out.length >= longCount}). */
    public static int packBits(int bits, int[] values, long[] out) {
        checkBits(bits);
        Natives n = natives;
        if (bitsOn && n != null && values.length >= minBatch) {
            try {
                int r = n.pack(bits, values, out);
                if (r >= 0) return r;
                fail("bits", r);
            } catch (Throwable t) {
                bitsOn = false;
                recordFailure("bits", t);
                LOG.warning("[Hybrid] bits nativo fallo (" + t + "); Java hasta reiniciar.");
            }
        }
        return JavaRef.pack(bits, values, out);
    }

    public static int unpackBits(int bits, int count, long[] longs, int[] out) {
        checkBits(bits);
        Natives n = natives;
        if (bitsOn && n != null && count >= minBatch) {
            try {
                int r = n.unpack(bits, count, longs, out);
                if (r >= 0) return r;
                fail("bits", r);
            } catch (Throwable t) {
                bitsOn = false;
                recordFailure("bits", t);
                LOG.warning("[Hybrid] bits nativo fallo (" + t + "); Java hasta reiniciar.");
            }
        }
        return JavaRef.unpack(bits, count, longs, out);
    }

    /** Ruido mejorado de Perlin por lote. {@code perm}: 256 entradas (ImprovedNoise.p); {@code xyz}: 3n doubles; {@code out}: n doubles. */
    public static void improvedNoiseBatch(byte[] perm, double[] xyz, int n, double[] out) {
        if (perm.length < 256 || xyz.length < n * 3 || out.length < n) throw new IllegalArgumentException("buffers cortos");
        Natives nat = natives;
        if (noiseOn && nat != null && n >= minBatch) {
            try {
                int r = nat.noise(perm, xyz, n, out);
                if (r == n) return;
                fail("noise", r);
            } catch (Throwable t) {
                noiseOn = false;
                recordFailure("noise", t);
                LOG.warning("[Hybrid] noise nativo fallo (" + t + "); Java hasta reiniciar.");
            }
        }
        JavaRef.noise(perm, xyz, n, out);
    }

    /** Analiza la cabecera de un .mca (solo lectura). {@code -> [presentes, corruptas, solapadas, maxSector, timestampMax]}. */
    public static long[] scanRegionHeader(byte[] header, long fileLen) {
        if (header.length < 8192) throw new IllegalArgumentException("cabecera < 8192");
        long[] out = new long[5];
        Natives n = natives;
        if (regionOn && n != null) {
            try {
                int r = n.region(header, fileLen, out);
                if (r >= 0) return out;
                fail("region", r);
            } catch (Throwable t) {
                regionOn = false;
                recordFailure("region", t);
                LOG.warning("[Hybrid] region nativo fallo (" + t + "); Java hasta reiniciar.");
            }
        }
        return JavaRef.region(header, fileLen);
    }

    // ---- NormalNoise nativo (density functions) --------------------------------------------------

    /** Registra un NormalNoise (blob de {@code HybridDensity.export}) en Rust. Devuelve handle &gt; 0, o &lt;= 0 si no disponible. */
    public static long normalNoiseCreate(byte[] blob) {
        Natives n = natives;
        if (n == null) return -1;
        try {
            return n.normalCreate(blob);
        } catch (Throwable t) {
            LOG.warning("[Hybrid] normalNoiseCreate fallo (" + t + ")");
            return -1;
        }
    }

    public static void normalNoiseFree(long handle) {
        Natives n = natives;
        if (n == null || handle <= 0) return;
        try {
            n.normalFree(handle);
        } catch (Throwable ignored) {
        }
    }

    /** Evalua n puntos; devuelve false si no hay biblioteca o falla (el llamador usa Java). */
    public static boolean normalNoiseBatch(long handle, double[] xyz, int n, double[] out) {
        Natives nat = natives;
        if (nat == null || handle <= 0 || xyz.length < n * 3 || out.length < n) return false;
        try {
            return nat.normalBatch(handle, xyz, n, out) == n;
        } catch (Throwable t) {
            return false;
        }
    }

    /** true si una operacion de bits de {@code n} valores iria a Rust ahora mismo. */
    public static boolean bitsActive(int n) {
        return bitsOn && natives != null && n >= minBatch;
    }

    public static boolean noiseActive(int n) {
        return noiseOn && natives != null && n >= minBatch;
    }

    private static void recordFailure(String what, Throwable t) {
        FAILURES.incrementAndGet();
        lastError = what + ": " + t;
    }

    /** Estado para /drakes hybrid: lo que la biblioteca y la configuracion realmente tienen ahora. */
    public record Snapshot(boolean initialized, boolean libraryLoaded, String libraryPath, int libraryAbi, int expectedAbi,
                           boolean bits, boolean noise, boolean region, int minBatch, long failures, String lastError) {}

    public static Snapshot snapshot() {
        return new Snapshot(initialized, natives != null, libraryPath, libraryAbi, ABI, bitsOn, noiseOn, regionOn, minBatch,
            FAILURES.get(), lastError);
    }

    private static void fail(String what, int code) {
        FAILURES.incrementAndGet();
        lastError = what + " devolvio " + code;
        throw new IllegalStateException(what + " devolvio " + code);
    }

    private static void checkBits(int bits) {
        if (bits < 1 || bits > 32) throw new IllegalArgumentException("bits fuera de 1..32: " + bits);
    }

    public static int longCount(int bits, int count) {
        int vpl = 64 / bits;
        return (count + vpl - 1) / vpl;
    }

    // ---- Implementacion nativa ------------------------------------------------------------------

    private static final class Natives {
        private final MethodHandle abi, pack, unpack, noise, region, nCreate, nFree, nBatch;

        Natives(Path lib) {
            SymbolLookup lk = SymbolLookup.libraryLookup(lib, Arena.global());
            Linker l = Linker.nativeLinker();
            var A = ValueLayout.ADDRESS;
            var I = ValueLayout.JAVA_INT;
            // critical(true): llamadas cortas, sin upcalls, aceptan segmentos de heap => cero copias ni asignaciones.
            var crit = Linker.Option.critical(true);
            abi = l.downcallHandle(lk.find("drakes_hybrid_abi_version").orElseThrow(), FunctionDescriptor.of(I));
            pack = l.downcallHandle(lk.find("drakes_bits_pack").orElseThrow(), FunctionDescriptor.of(I, I, I, A, A, I), crit);
            unpack = l.downcallHandle(lk.find("drakes_bits_unpack").orElseThrow(), FunctionDescriptor.of(I, I, I, A, I, A), crit);
            noise = l.downcallHandle(lk.find("drakes_noise_improved_batch").orElseThrow(), FunctionDescriptor.of(I, A, A, I, A), crit);
            region = l.downcallHandle(lk.find("drakes_region_scan_header").orElseThrow(),
                FunctionDescriptor.of(I, A, I, ValueLayout.JAVA_LONG, A), crit);
            var L = ValueLayout.JAVA_LONG;
            nCreate = l.downcallHandle(lk.find("drakes_noise_create").orElseThrow(), FunctionDescriptor.of(L, A, I), crit);
            nFree = l.downcallHandle(lk.find("drakes_noise_free").orElseThrow(), FunctionDescriptor.ofVoid(L), crit);
            nBatch = l.downcallHandle(lk.find("drakes_normal_noise_batch").orElseThrow(), FunctionDescriptor.of(I, L, A, I, A), crit);
        }

        long normalCreate(byte[] blob) throws Throwable {
            return (long) nCreate.invokeExact(MemorySegment.ofArray(blob), blob.length);
        }

        void normalFree(long h) throws Throwable {
            nFree.invokeExact(h);
        }

        int normalBatch(long h, double[] xyz, int n, double[] out) throws Throwable {
            return (int) nBatch.invokeExact(h, MemorySegment.ofArray(xyz), n, MemorySegment.ofArray(out));
        }

        int abi() throws Throwable {
            return (int) abi.invokeExact();
        }

        int pack(int bits, int[] v, long[] out) throws Throwable {
            return (int) pack.invokeExact(bits, v.length, MemorySegment.ofArray(v), MemorySegment.ofArray(out), out.length);
        }

        int unpack(int bits, int count, long[] longs, int[] out) throws Throwable {
            if (out.length < count) return -2;
            return (int) unpack.invokeExact(bits, count, MemorySegment.ofArray(longs), longs.length, MemorySegment.ofArray(out));
        }

        int noise(byte[] perm, double[] xyz, int n, double[] out) throws Throwable {
            return (int) noise.invokeExact(MemorySegment.ofArray(perm), MemorySegment.ofArray(xyz), n, MemorySegment.ofArray(out));
        }

        int region(byte[] header, long fileLen, long[] out) throws Throwable {
            return (int) region.invokeExact(MemorySegment.ofArray(header), header.length, fileLen, MemorySegment.ofArray(out));
        }
    }

    // ---- Referencia Java (misma semantica que el nucleo Rust) ----------------------------------------

    /** Implementacion Java de referencia y de fallback. Publica para que los tests/benchmarks la contrasten. */
    public static final class JavaRef {
        private JavaRef() {}

        public static int pack(int bits, int[] values, long[] out) {
            int vpl = 64 / bits;
            int need = longCount(bits, values.length);
            if (out.length < need) throw new IllegalArgumentException("out corto: " + out.length + " < " + need);
            long mask = bits == 32 ? 0xFFFFFFFFL : (1L << bits) - 1;
            for (int w = 0, i = 0; w < need; w++) {
                long word = 0;
                for (int s = 0; s < vpl && i < values.length; s++, i++) {
                    // Igual que vanilla SimpleBitStorage(bits, size, int[]): enmascara, no rechaza.
                    word |= (values[i] & mask) << (s * bits);
                }
                out[w] = word;
            }
            return need;
        }

        public static int unpack(int bits, int count, long[] longs, int[] out) {
            int vpl = 64 / bits;
            if (longs.length < longCount(bits, count) || out.length < count) throw new IllegalArgumentException("buffers cortos");
            long mask = bits == 32 ? 0xFFFFFFFFL : (1L << bits) - 1;
            for (int i = 0; i < count; i++) {
                out[i] = (int) ((longs[i / vpl] >>> ((i % vpl) * bits)) & mask);
            }
            return count;
        }

        private static final int[][] GRAD = {{1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0}, {1, 0, 1}, {-1, 0, 1}, {1, 0, -1},
            {-1, 0, -1}, {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}, {1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}};

        private static double fade(double t) {
            return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
        }

        private static double lerp(double t, double a, double b) {
            return a + t * (b - a);
        }

        private static int floor(double v) {
            int i = (int) v;
            return v < i ? i - 1 : i;
        }

        public static double noise(byte[] p, double x, double y, double z) {
            int x0 = floor(x), y0 = floor(y), z0 = floor(z);
            double xr = x - x0, yr = y - y0, zr = z - z0;
            int a = p[x0 & 255] & 255, b = p[(x0 + 1) & 255] & 255;
            int aa = p[(a + y0) & 255] & 255, ab = p[(a + y0 + 1) & 255] & 255;
            int ba = p[(b + y0) & 255] & 255, bb = p[(b + y0 + 1) & 255] & 255;
            double d000 = dot(p[(aa + z0) & 255], xr, yr, zr);
            double d100 = dot(p[(ba + z0) & 255], xr - 1, yr, zr);
            double d010 = dot(p[(ab + z0) & 255], xr, yr - 1, zr);
            double d110 = dot(p[(bb + z0) & 255], xr - 1, yr - 1, zr);
            double d001 = dot(p[(aa + z0 + 1) & 255], xr, yr, zr - 1);
            double d101 = dot(p[(ba + z0 + 1) & 255], xr - 1, yr, zr - 1);
            double d011 = dot(p[(ab + z0 + 1) & 255], xr, yr - 1, zr - 1);
            double d111 = dot(p[(bb + z0 + 1) & 255], xr - 1, yr - 1, zr - 1);
            double fx = fade(xr), fy = fade(yr), fz = fade(zr);
            return lerp(fz, lerp(fy, lerp(fx, d000, d100), lerp(fx, d010, d110)),
                lerp(fy, lerp(fx, d001, d101), lerp(fx, d011, d111)));
        }

        private static double dot(byte h, double x, double y, double z) {
            int[] g = GRAD[h & 15];
            return g[0] * x + g[1] * y + g[2] * z;
        }

        public static void noise(byte[] perm, double[] xyz, int n, double[] out) {
            for (int i = 0; i < n; i++) out[i] = noise(perm, xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]);
        }

        public static long[] region(byte[] h, long fileLen) {
            long sectors = (fileLen + 4095) / 4096;
            boolean[] owner = new boolean[(int) Math.max(sectors, 2)];
            owner[0] = owner[1] = true;
            long present = 0, corrupt = 0, overlap = 0, maxS = 0, newest = 0;
            for (int i = 0; i < 1024; i++) {
                long off = ((h[i * 4] & 255L) << 16) | ((h[i * 4 + 1] & 255L) << 8) | (h[i * 4 + 2] & 255L);
                long cnt = h[i * 4 + 3] & 255L;
                if (off == 0 && cnt == 0) continue;
                present++;
                if (off < 2 || cnt == 0 || off + cnt > sectors) {
                    corrupt++;
                    continue;
                }
                boolean bad = false;
                for (long s = off; s < off + cnt; s++) {
                    if (owner[(int) s]) bad = true;
                    owner[(int) s] = true;
                }
                if (bad) overlap++;
                maxS = Math.max(maxS, off + cnt - 1);
                long t = ((h[4096 + i * 4] & 255L) << 24) | ((h[4096 + i * 4 + 1] & 255L) << 16)
                    | ((h[4096 + i * 4 + 2] & 255L) << 8) | (h[4096 + i * 4 + 3] & 255L);
                newest = Math.max(newest, t);
            }
            return new long[] {present, corrupt, overlap, maxS, newest};
        }
    }
}
