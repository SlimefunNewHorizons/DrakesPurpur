import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Random;
import java.util.function.IntSupplier;
import org.purpurmc.purpur.nativelib.DrakesNative;
import org.purpurmc.purpur.nativelib.PulseCounter;

/** Benchmark reproducible Java vs nativo (ver docs/NATIVO.md). Uso: NativeBench <directorio-con-los-.so> */
public class NativeBench {
    static final com.sun.management.ThreadMXBean MX = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    static volatile long sink;

    static void row(String name, String n, IntSupplier body, int iters) {
        for (int w = 0; w < 3; w++) run(body, iters / 2); // calentamiento (C2)
        double[] t = new double[7];
        long alloc = 0;
        for (int r = 0; r < t.length; r++) {
            long a0 = MX.getCurrentThreadAllocatedBytes();
            long t0 = System.nanoTime();
            run(body, iters);
            t[r] = (System.nanoTime() - t0) / (double) iters;
            alloc = MX.getCurrentThreadAllocatedBytes() - a0;
        }
        java.util.Arrays.sort(t);
        System.out.printf("%-34s %-9s %9.1f ns/op  %7.1f B/op%n", name, n, t[t.length / 2], alloc / (double) iters);
    }

    static void run(IntSupplier b, int iters) {
        int s = 0;
        for (int i = 0; i < iters; i++) s += b.getAsInt();
        sink = s;
    }

    record Key(int w, int x, int y, int z) {}

    /** Linea base "ingenua": lo que haria un PulseWindow con ArrayDeque<Long> por bloque. */
    static final class Naive implements PulseCounter {
        final HashMap<Key, ArrayDeque<Long>> m = new HashMap<>();
        public long record(int w, int x, int y, int z, long now, long lm, long fm) {
            ArrayDeque<Long> d = m.computeIfAbsent(new Key(w, x, y, z), k -> new ArrayDeque<>());
            d.addLast(now);
            while (!d.isEmpty() && d.peekFirst() < now - lm) d.pollFirst();
            int fast = 0;
            var it = d.descendingIterator();
            while (it.hasNext() && it.next() >= now - fm) fast++;
            return ((long) fast << 32) | d.size();
        }
        public void remove(int w, int x, int y, int z) { m.remove(new Key(w, x, y, z)); }
        public int sweep(long now, long idle) { return 0; }
        public void clear() { m.clear(); }
        public int size() { return m.size(); }
    }

    public static void main(String[] a) {
        String dir = a.length > 0 ? a[0] : "natives";
        DrakesNative.init(new DrakesNative.Settings(true, dir, true, true, true, true, true, false, 64, 8192));
        System.out.println("java=" + Runtime.version() + "  " + DrakesNative.status());
        Random rnd = new Random(42);

        // ---- correctitud cruzada ----
        PulseCounter nat = DrakesNative.pulses(), jv = new PulseCounter.Java(64, 8192);
        nat.clear();
        long now = 1_000_000;
        for (int i = 0; i < 200_000; i++) {
            now += rnd.nextInt(40);
            int x = rnd.nextInt(300), z = rnd.nextInt(3);
            long r1 = nat.record(1, x, 64, z, now, 1000, 200), r2 = jv.record(1, x, 64, z, now, 1000, 200);
            if (r1 != r2) throw new AssertionError("pulso distinto en " + i + ": " + r1 + " vs " + r2);
        }
        System.out.println("correctitud pulsos: OK (200000 llamadas identicas)");
        int[] cap = new int[1024], c1 = new int[1024], c2 = new int[1024], o1 = new int[2], o2 = new int[2];
        for (int i = 0; i < 1024; i++) { cap[i] = 1 + rnd.nextInt(5000); c1[i] = c2[i] = rnd.nextInt(cap[i]); }
        DrakesNative.fillSequential(40_000, cap, c1, 1024, o1);
        DrakesNative.fillJava(40_000, cap, c2, 1024, o2);
        if (!java.util.Arrays.equals(c1, c2) || !java.util.Arrays.equals(o1, o2)) throw new AssertionError("fill distinto");
        System.out.println("correctitud fill: OK\n");

        // ---- pulsos de redstone: 512 bloques activos, relojes ~ 5 pulsos/s ----
        int N = 1 << 14;
        int[] xs = new int[N];
        for (int i = 0; i < N; i++) xs[i] = rnd.nextInt(512);
        PulseCounter[] impl = {new Naive(), new PulseCounter.Java(64, 8192), nat};
        String[] names = {"pulso Java ingenuo (HashMap+Deque)", "pulso Java sin alloc (anillo)", "pulso Rust Panama"};
        for (int k = 0; k < 3; k++) {
            PulseCounter p = impl[k];
            long[] t = {5_000_000};
            int[] i = {0};
            row(names[k], "512 bloques", () -> { t[0] += 3; int j = i[0]++ & (N - 1); return (int) p.record(1, xs[j], 64, 0, t[0], 1000, 200); }, 2_000_000);
        }
        System.out.println();

        // ---- aura por lotes ----
        for (int n : new int[] {10, 100, 1000, 10000}) {
            double[] pts = new double[3 * n];
            byte[] out = new byte[n];
            for (int i = 0; i < pts.length; i++) pts[i] = rnd.nextDouble() * 60 - 30;
            int it = 4_000_000 / Math.max(1, n / 10);
            row("aura Java", "n=" + n, () -> DrakesNative.auraJava(0, 0, 0, 20, n, pts, out), it);
            row("aura Rust Panama (critical)", "n=" + n, () -> DrakesNative.auraBatch(0, 0, 0, 20, n, pts, out), it);
        }
        System.out.println();

        // ---- Slimefun ----
        for (int n : new int[] {2, 16, 128, 1024, 8192}) {
            int[] cp = new int[n], ch = new int[n], base = new int[n], o = new int[2];
            for (int i = 0; i < n; i++) { cp[i] = 100 + rnd.nextInt(5000); base[i] = rnd.nextInt(cp[i]); }
            int it = Math.max(2000, 40_000_000 / (n + 8));
            row("suma saturada Java", "n=" + n, () -> DrakesNative.sumJava(cp, n), it);
            row("suma saturada Rust Panama", "n=" + n, () -> DrakesNative.sumSaturating(cp, n), it);
            row("fill Java (+reset)", "n=" + n, () -> { System.arraycopy(base, 0, ch, 0, n); return DrakesNative.fillJava(1 << 20, cp, ch, n, o); }, it);
            row("fill Rust Panama (+reset)", "n=" + n, () -> { System.arraycopy(base, 0, ch, 0, n); return DrakesNative.fillSequential(1 << 20, cp, ch, n, o); }, it);
            row("store Java", "n=" + n, () -> DrakesNative.storeJava(n * 40, cp, ch, n), it);
            row("store Rust Panama", "n=" + n, () -> DrakesNative.storeRemaining(n * 40, cp, ch, n), it);
        }
        System.out.println();
        row("precio mercado Java", "-", () -> (int) DrakesNative.priceJava(100, 37, 5e8, 1e8, 0.85, 1.85, 0.02, 1.45, 1.0), 5_000_000);
        row("precio mercado Rust Panama", "-", () -> (int) DrakesNative.marketPrice(100, 37, 5e8, 1e8, 0.85, 1.85, 0.02, 1.45, 1.0), 5_000_000);
    }
}
