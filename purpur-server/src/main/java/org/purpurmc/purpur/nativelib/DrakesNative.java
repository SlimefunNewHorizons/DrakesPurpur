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
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Puente opcional a las bibliotecas nativas (Odysseia-Rust, Slimefun-Rust) por Panama (FFM, sin JNI).
 * <ul>
 *   <li>Apagado por defecto: {@code drakes-data/perf.yml -> native.enabled: false}.</li>
 *   <li>Cada ruta tiene su propio interruptor ({@code native.odysseia.*}, {@code native.slimefun.*});
 *       solo se activan por defecto las que ganaron el benchmark (docs/NATIVO.md).</li>
 *   <li>Si falta el .so/.dll, la ABI no coincide o un downcall falla, se usa Java (misma semantica)
 *       y se deja un log; un fallo en runtime apaga esa ruta nativa para el resto de la ejecucion.</li>
 * </ul>
 * Los plugins (Odysseia, Slimefun) pueden llamar estos metodos estaticos: la clase vive en el classpath del servidor.
 */
public final class DrakesNative {
    private static final Logger LOG = Logger.getLogger("DrakesNative");
    public static final int ODYSSEIA_ABI = 1;
    public static final int SLIMEFUN_ABI_MIN = 2, SLIMEFUN_ABI_MAX = 2;
    private static final int ERR = Integer.MIN_VALUE;

    /** Interruptores efectivos (false = Java). */
    private static volatile boolean master, tickEngine;
    private static volatile boolean pulses, aura, fill, sum, price;
    private static volatile SymbolLookup odysseia, slimefun;
    private static volatile String odysseiaPath, slimefunPath;
    private static volatile boolean initialized;
    private static volatile PulseCounter pulseCounter;

    private DrakesNative() {}

    /** Configuracion minima (sin Bukkit para poder medirla en un benchmark suelto). */
    public record Settings(boolean enabled, String dir, boolean pulses, boolean aura, boolean fill, boolean sum, boolean price,
                           boolean tickEngine, int ringCap, int maxEntries) {
        public static Settings off() {
            return new Settings(false, "natives", false, false, false, false, false, false, 64, 8192);
        }
    }

    public static boolean enabled() {
        return master;
    }

    /** true solo si native.enabled y native.slimefun.tick-engine: permite a SlimefunRustEngine cargar su biblioteca. */
    public static boolean tickEngineAllowed() {
        return tickEngine;
    }

    public static synchronized void init(Settings s) {
        if (initialized) return;
        initialized = true;
        tickEngine = s.enabled() && s.tickEngine();
        pulseCounter = new PulseCounter.Java(s.ringCap(), s.maxEntries());
        if (!s.enabled()) {
            LOG.info("[Native] apagado (native.enabled=false): todo corre en Java.");
            return;
        }
        if (Runtime.version().feature() < 22) {
            LOG.warning("[Native] requiere Java 22+ (FFM); se usa Java.");
            return;
        }
        boolean any = false;
        if (s.pulses() || s.aura()) {
            odysseia = open(s.dir(), "odysseia_ffi", "odysseia_abi_version", ODYSSEIA_ABI, ODYSSEIA_ABI, true);
            if (odysseia != null) {
                try {
                    if (s.pulses()) {
                        if (Odysseia.init(s.ringCap(), s.maxEntries()) == 0) {
                            pulseCounter = new Odysseia.Native();
                            pulses = true;
                        } else {
                            LOG.warning("[Native] odysseia_pulse_init fallo; pulsos en Java.");
                        }
                    }
                    aura = s.aura() && Odysseia.hasAura();
                    any = pulses || aura;
                } catch (Throwable t) {
                    LOG.warning("[Native] Odysseia no se pudo enlazar (" + t + "); se usa Java.");
                    pulses = aura = false;
                }
            }
        }
        if (s.fill() || s.sum() || s.price()) {
            slimefun = open(s.dir(), "slimefun_ffi", "slimefun_abi_version", SLIMEFUN_ABI_MIN, SLIMEFUN_ABI_MAX, false);
            if (slimefun != null) {
                try {
                    Slimefun.bind();
                    fill = s.fill();
                    sum = s.sum();
                    price = s.price();
                    any |= fill || sum || price;
                } catch (Throwable t) {
                    LOG.warning("[Native] Slimefun no se pudo enlazar (" + t + "); se usa Java.");
                }
            }
        }
        master = any;
        LOG.info("[Native] " + status());
    }

    public static String status() {
        return "enabled=" + master + " odysseia=" + (odysseiaPath == null ? "no cargado" : odysseiaPath)
            + " [pulses=" + pulses + " aura=" + aura + "] slimefun=" + (slimefunPath == null ? "no cargado" : slimefunPath)
            + " [fill=" + fill + " sum=" + sum + " price=" + price + "]";
    }

    // ---- busqueda y carga ------------------------------------------------------------------

    private static SymbolLookup open(String dir, String base, String abiSym, int abiMin, int abiMax, boolean isOdysseia) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String file = os.contains("win") ? base + ".dll" : os.contains("mac") ? "lib" + base + ".dylib" : "lib" + base + ".so";
        List<Path> cand = new ArrayList<>();
        String prop = System.getProperty("drakes.native.dir");
        String env = System.getenv("DRAKES_NATIVE_DIR");
        for (String d : new String[] {prop, env, dir, "drakes-data/natives", "natives"}) {
            if (d != null && !d.isBlank()) cand.add(Path.of(d, file));
        }
        for (Path p : cand) {
            if (!Files.isRegularFile(p)) continue;
            try {
                SymbolLookup lk = SymbolLookup.libraryLookup(p.toAbsolutePath(), Arena.global());
                var sym = lk.find(abiSym);
                if (sym.isEmpty()) {
                    LOG.warning("[Native] " + p + " sin " + abiSym + " (biblioteca antigua); se usa Java.");
                    return null;
                }
                int abi = (int) Linker.nativeLinker().downcallHandle(sym.get(), FunctionDescriptor.of(ValueLayout.JAVA_INT)).invokeExact();
                if (abi < abiMin || abi > abiMax) {
                    LOG.warning("[Native] " + p + " ABI " + abi + " no soportada (" + abiMin + "-" + abiMax + "); se usa Java.");
                    return null;
                }
                if (isOdysseia) odysseiaPath = p.toString();
                else slimefunPath = p.toString();
                return lk;
            } catch (Throwable t) {
                LOG.warning("[Native] no se pudo abrir " + p + ": " + t + "; se usa Java.");
                return null;
            }
        }
        LOG.info("[Native] " + file + " no encontrado (buscado en " + cand + "); se usa Java.");
        return null;
    }

    private static MethodHandle h(SymbolLookup lk, String name, FunctionDescriptor fd, Linker.Option... o) {
        return Linker.nativeLinker().downcallHandle(lk.find(name).orElseThrow(() -> new IllegalStateException("falta " + name)), fd, o);
    }

    private static void fail(String ruta, Throwable t) {
        LOG.warning("[Native] fallo en " + ruta + " (" + t + "); esa ruta vuelve a Java hasta reiniciar.");
        switch (ruta) {
            case "pulses" -> pulses = false;
            case "aura" -> aura = false;
            case "fill" -> fill = false;
            case "sum" -> sum = false;
            case "price" -> price = false;
            default -> { }
        }
    }

    // ---- Odysseia --------------------------------------------------------------------------

    private static final class Odysseia {
        private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
        private static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;
        private static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE;
        static final MethodHandle INIT = h(odysseia, "odysseia_pulse_init", FunctionDescriptor.of(I, I, I));
        static final MethodHandle RECORD = h(odysseia, "odysseia_pulse_record", FunctionDescriptor.of(L, I, I, I, I, L, L, L), Linker.Option.critical(false));
        static final MethodHandle REMOVE = h(odysseia, "odysseia_pulse_remove", FunctionDescriptor.of(I, I, I, I, I), Linker.Option.critical(false));
        static final MethodHandle SWEEP = h(odysseia, "odysseia_pulse_sweep", FunctionDescriptor.of(I, L, L));
        static final MethodHandle CLEAR = h(odysseia, "odysseia_pulse_clear", FunctionDescriptor.of(I));
        static final MethodHandle LEN = h(odysseia, "odysseia_pulse_len", FunctionDescriptor.of(I));
        static final MethodHandle AURA = tryAura();

        static MethodHandle tryAura() {
            try {
                return h(odysseia, "odysseia_aura_batch", FunctionDescriptor.of(I, D, D, D, D, I, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    Linker.Option.critical(true));
            } catch (Throwable t) {
                return null;
            }
        }

        static boolean hasAura() {
            return AURA != null;
        }

        static int init(int ring, int max) throws Throwable {
            return (int) INIT.invokeExact(ring, max);
        }

        static final class Native implements PulseCounter {
            @Override
            public long record(int w, int x, int y, int z, long now, long lm, long fm) {
                try {
                    return (long) RECORD.invokeExact(w, x, y, z, now, lm, fm);
                } catch (Throwable t) {
                    fail("pulses", t);
                    return -1;
                }
            }

            @Override
            public void remove(int w, int x, int y, int z) {
                try {
                    int r = (int) REMOVE.invokeExact(w, x, y, z);
                } catch (Throwable t) {
                    fail("pulses", t);
                }
            }

            @Override
            public int sweep(long now, long idle) {
                try {
                    return (int) SWEEP.invokeExact(now, idle);
                } catch (Throwable t) {
                    fail("pulses", t);
                    return -1;
                }
            }

            @Override
            public void clear() {
                try {
                    int r = (int) CLEAR.invokeExact();
                } catch (Throwable t) {
                    fail("pulses", t);
                }
            }

            @Override
            public int size() {
                try {
                    return (int) LEN.invokeExact();
                } catch (Throwable t) {
                    fail("pulses", t);
                    return -1;
                }
            }
        }
    }

    /** Contador de pulsos activo (nativo si esta activado y sano, si no Java). Nunca null. */
    public static PulseCounter pulses() {
        PulseCounter p = pulseCounter;
        if (p == null) {
            init(Settings.off());
            p = pulseCounter;
        }
        return p;
    }

    /**
     * Prueba de aura para todos los jugadores de una vez: {@code points} = 3*n doubles (x,y,z...),
     * {@code out} = n bytes (1 dentro, 0 fuera). Devuelve cuantos estan dentro.
     */
    public static int auraBatch(double bx, double by, double bz, double radius, int n, double[] points, byte[] out) {
        if (n <= 0) return 0;
        if (aura) {
            try {
                int r = (int) Odysseia.AURA.invokeExact(bx, by, bz, radius, n, MemorySegment.ofArray(points), MemorySegment.ofArray(out));
                if (r >= 0) return r;
            } catch (Throwable t) {
                fail("aura", t);
            }
        }
        return auraJava(bx, by, bz, radius, n, points, out);
    }

    public static int auraJava(double bx, double by, double bz, double radius, int n, double[] p, byte[] out) {
        double r2 = radius * radius;
        int hits = 0;
        for (int i = 0, j = 0; i < n; i++, j += 3) {
            double dx = p[j] - bx, dy = p[j + 1] - by, dz = p[j + 2] - bz;
            boolean in = dx * dx + dy * dy + dz * dz <= r2;
            out[i] = (byte) (in ? 1 : 0);
            if (in) hits++;
        }
        return hits;
    }

    // ---- Slimefun --------------------------------------------------------------------------

    private static final class Slimefun {
        private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
        private static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;
        private static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE;
        static final MethodHandle SUM = h(slimefun, "slimefun_sum_saturating", FunctionDescriptor.of(I, ValueLayout.ADDRESS, L), Linker.Option.critical(true));
        static final MethodHandle FILL = h(slimefun, "slimefun_fill_sequential",
            FunctionDescriptor.of(I, I, ValueLayout.ADDRESS, ValueLayout.ADDRESS, L, ValueLayout.ADDRESS), Linker.Option.critical(true));
        static final MethodHandle STORE = h(slimefun, "slimefun_store_remaining",
            FunctionDescriptor.of(I, I, ValueLayout.ADDRESS, ValueLayout.ADDRESS, L), Linker.Option.critical(true));
        static final MethodHandle PRICE = h(slimefun, "slimefun_market_price", FunctionDescriptor.of(D, D, L, D, D, D, D, D, D, D),
            Linker.Option.critical(false));

        /** Fuerza la inicializacion: si falta un simbolo falla aqui y no en caliente. */
        static void bind() {
            if (SUM == null || FILL == null || STORE == null || PRICE == null) throw new IllegalStateException("simbolos");
        }
    }

    /** Suma saturada (como EnergyNet). */
    public static int sumSaturating(int[] v, int n) {
        if (sum && n > 0) {
            try {
                return (int) Slimefun.SUM.invokeExact(MemorySegment.ofArray(v), (long) n);
            } catch (Throwable t) {
                fail("sum", t);
            }
        }
        return sumJava(v, n);
    }

    public static int sumJava(int[] v, int n) {
        long s = 0;
        for (int i = 0; i < n; i++) s += v[i];
        return s > Integer.MAX_VALUE ? Integer.MAX_VALUE : s < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) s;
    }

    /**
     * Reparto secuencial (EnergyNet): rellena {@code charge} en orden hasta {@code capacity} con {@code supply}.
     * {@code out2[0]} = energia restante, {@code out2[1]} = demanda. Devuelve 0 o {@link #ERR_CODE}.
     */
    public static int fillSequential(int supply, int[] capacity, int[] charge, int n, int[] out2) {
        if (fill && n > 0) {
            try {
                int r = (int) Slimefun.FILL.invokeExact(supply, MemorySegment.ofArray(capacity), MemorySegment.ofArray(charge), (long) n,
                    MemorySegment.ofArray(out2));
                if (r != ERR) return r;
            } catch (Throwable t) {
                fail("fill", t);
            }
        }
        return fillJava(supply, capacity, charge, n, out2);
    }

    public static int fillJava(int supply, int[] cap, int[] ch, int n, int[] out2) {
        int remaining = supply;
        long demand = 0;
        for (int i = 0; i < n; i++) {
            int cur = ch[i];
            if (cur < cap[i]) {
                int space = cap[i] - cur;
                demand += space;
                if (remaining > 0) {
                    if (remaining > space) {
                        ch[i] = cap[i];
                        remaining -= space;
                    } else {
                        ch[i] = cur + remaining;
                        remaining = 0;
                    }
                }
            }
        }
        out2[0] = remaining;
        out2[1] = demand > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) demand;
        return 0;
    }

    public static final int ERR_CODE = ERR;

    /** Reparte el sobrante en orden y deja el resto a 0. Devuelve lo que no cupo (o {@link #ERR_CODE}). */
    public static int storeRemaining(int remaining, int[] capacity, int[] out, int n) {
        if (fill && n > 0) {
            try {
                int r = (int) Slimefun.STORE.invokeExact(remaining, MemorySegment.ofArray(capacity), MemorySegment.ofArray(out), (long) n);
                if (r != ERR) return r;
            } catch (Throwable t) {
                fail("fill", t);
            }
        }
        return storeJava(remaining, capacity, out, n);
    }

    public static int storeJava(int remaining, int[] cap, int[] out, int n) {
        int left = remaining;
        for (int i = 0; i < n; i++) {
            if (left > 0) {
                if (left > cap[i]) {
                    out[i] = cap[i];
                    left -= cap[i];
                } else {
                    out[i] = left;
                    left = 0;
                }
            } else {
                out[i] = 0;
            }
        }
        return left;
    }

    /** Precio de mercado de DrakesSlimeMarket. */
    public static double marketPrice(double base, long demand, double wealth, double refWealth, double minF, double maxF, double step,
                                     double maxDemandF, double pulseF) {
        if (price) {
            try {
                double r = (double) Slimefun.PRICE.invokeExact(base, demand, wealth, refWealth, minF, maxF, step, maxDemandF, pulseF);
                if (!Double.isNaN(r)) return r;
            } catch (Throwable t) {
                fail("price", t);
            }
        }
        return priceJava(base, demand, wealth, refWealth, minF, maxF, step, maxDemandF, pulseF);
    }

    public static double priceJava(double base, long demand, double wealth, double refWealth, double minF, double maxF, double step,
                                   double maxDemandF, double pulseF) {
        double wr = Math.max(0.0, wealth) / Math.max(refWealth, 1.0);
        double wf = 0.90 + Math.log1p(wr) / Math.log(11.0) * 0.65;
        double df = Math.min(maxDemandF, 1.0 + Math.max(demand, 0) * step);
        double c = Math.min(maxF, Math.max(minF, wf * df * pulseF));
        return Math.max(Math.round(base * c * 100.0), 1) / 100.0;
    }
}
