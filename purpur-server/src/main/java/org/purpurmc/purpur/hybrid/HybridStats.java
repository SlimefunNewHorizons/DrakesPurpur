package org.purpurmc.purpur.hybrid;

import java.util.concurrent.atomic.LongAdder;

/**
 * Contadores baratos de la tuberia de chunks (instrumentacion del Hybrid Engine). Apagados por defecto
 * ({@code native.hybrid.stats}); con ellos apagados cada sitio instrumentado cuesta una lectura de un boolean.
 */
public final class HybridStats {
    /**
     * IMPORTANTE: quien instrumenta debe leer este campo UNA sola vez por llamada. Si se activa mientras otro hilo esta
     * a mitad de una llamada que empezo con t=0, {@code add} sumaria el reloj monotono absoluto (miles de segundos):
     * esa carrera explica los ~20 ms/llamada anomalos de bits.unpack.java en el CI run 42321107189.
     */
    public static volatile boolean enabled;

    public static final class Stage {
        public final String name;
        public final LongAdder calls = new LongAdder(), nanos = new LongAdder(), items = new LongAdder();
        /** Peor llamada: un maximo muy superior a la media delata descheduling/pausas del runner, no coste del kernel. */
        public final java.util.concurrent.atomic.LongAccumulator max = new java.util.concurrent.atomic.LongAccumulator(Math::max, 0L);

        Stage(String name) {
            this.name = name;
        }

        public void add(long startNanos, int itemCount) {
            calls.increment();
            if (startNanos == 0L) return;   // la llamada empezo con las estadisticas apagadas
            long d = System.nanoTime() - startNanos;
            nanos.add(d);
            max.accumulate(d);
            items.add(itemCount);
        }

        public String line() {
            long c = calls.sum(), n = nanos.sum();
            return String.format("%-22s calls=%d items=%d total=%.2fms avg=%.0fns/call max=%.3fms (tiempo de pared; incluye descheduling)", name, c, items.sum(), n / 1e6, c == 0 ? 0 : (double) n / c, max.get() / 1e6);
        }

        public void reset() {
            calls.reset();
            nanos.reset();
            items.reset();
            max.reset();
        }
    }

    public static final Stage PACK_JAVA = new Stage("bits.pack.java");
    public static final Stage PACK_NATIVE = new Stage("bits.pack.rust");
    public static final Stage UNPACK_JAVA = new Stage("bits.unpack.java");
    public static final Stage UNPACK_NATIVE = new Stage("bits.unpack.rust");
    public static final Stage[] ALL = {PACK_JAVA, PACK_NATIVE, UNPACK_JAVA, UNPACK_NATIVE};

    private HybridStats() {}

    public static void resetAll() {
        for (Stage s : ALL) s.reset();
    }

    public static String report() {
        StringBuilder sb = new StringBuilder();
        for (Stage s : ALL) sb.append(s.line()).append('\n');
        return sb.toString();
    }
}
