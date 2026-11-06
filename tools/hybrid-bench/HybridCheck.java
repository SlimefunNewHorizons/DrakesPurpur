import java.nio.file.*;
import java.util.*;
import org.purpurmc.purpur.nativelib.HybridNative;
import org.purpurmc.purpur.nativelib.HybridNative.JavaRef;

/** Paridad Java vs Rust (bit a bit) + benchmark. Uso: HybridCheck <dir-con-lib> [--bench]. Sale con codigo != 0 si algo difiere. */
public class HybridCheck {
    static int fails;
    static void check(boolean ok, String what) {
        if (!ok) { fails++; System.out.println("FAIL " + what); } else System.out.println("ok   " + what);
    }

    public static void main(String[] a) throws Exception {
        String dir = a[0]; dirG = dir;
        boolean bench = a.length > 1 && a[1].equals("--bench");
        // 0) Con el motor apagado debe funcionar igual (fallback Java).
        HybridNative.init(HybridNative.Settings.off());
        long[] l0 = new long[HybridNative.longCount(5, 4096)];
        HybridNative.packBits(5, new int[4096], l0);
        check(true, "apagado: packBits funciona en Java");
        HybridNative.resetForTests();
        HybridNative.init(new HybridNative.Settings(true, dir, true, true, true, 1));
        check(HybridNative.status().contains("bits=true"), "biblioteca nativa cargada: " + HybridNative.status());

        Random r = new Random(42);
        // 1) bits: todos los anchos
        for (int bits = 1; bits <= 32; bits++) {
            int n = 4096;
            int[] v = new int[n];
            for (int i = 0; i < n; i++) v[i] = bits >= 31 ? (bits == 32 ? r.nextInt() : r.nextInt() & 0x7FFFFFFF) : r.nextInt(1 << bits);
            long[] nat = new long[HybridNative.longCount(bits, n)], ref = new long[nat.length];
            HybridNative.packBits(bits, v, nat);
            JavaRef.pack(bits, v, ref);
            boolean same = Arrays.equals(nat, ref);
            int[] back = new int[n];
            HybridNative.unpackBits(bits, n, nat, back);
            check(same && Arrays.equals(back, v), "bits=" + bits + " pack/unpack identico a Java");
        }
        {
            int[] big = {0x1F, 0xFF3, -1, 7}; long[] a1 = new long[1], a2 = new long[1];
            HybridNative.packBits(4, big, a1); JavaRef.pack(4, big, a2);
            check(a1[0] == a2[0] && a1[0] == 0x7F3FL, "enmascarado como vanilla: " + Long.toHexString(a1[0]) + " == " + Long.toHexString(a2[0]));
        }

        // 2) noise: bit a bit
        byte[] perm = new byte[256];
        List<Integer> idx = new ArrayList<>(); for (int i = 0; i < 256; i++) idx.add(i);
        Collections.shuffle(idx, new Random(7)); for (int i = 0; i < 256; i++) perm[i] = (byte) (int) idx.get(i);
        int N = 100_000; double[] xyz = new double[N * 3];
        for (int i = 0; i < xyz.length; i++) xyz[i] = (r.nextDouble() - 0.5) * 4000;
        double[] on = new double[N], oj = new double[N];
        HybridNative.improvedNoiseBatch(perm, xyz, N, on);
        JavaRef.noise(perm, xyz, N, oj);
        int diff = 0; double maxd = 0;
        for (int i = 0; i < N; i++) { if (Double.doubleToLongBits(on[i]) != Double.doubleToLongBits(oj[i])) { diff++; maxd = Math.max(maxd, Math.abs(on[i] - oj[i])); } }
        check(diff == 0, "noise " + N + " puntos: " + diff + " difieren (max |d|=" + maxd + ")");

        // 3) region: cabecera sintetica
        byte[] h = new byte[8192];
        h[0] = 0; h[1] = 0; h[2] = 2; h[3] = 2; h[4] = 0; h[5] = 0; h[6] = 3; h[7] = 1; h[8] = 0; h[9] = 0; h[10] = 100; h[11] = 1;
        h[4096 + 4 + 3] = 9;
        long[] rn = HybridNative.scanRegionHeader(h, 5 * 4096L), rj = JavaRef.region(h, 5 * 4096L);
        check(Arrays.equals(rn, rj) && rn[0] == 3 && rn[1] == 1 && rn[2] == 1, "region " + Arrays.toString(rn));

        if (bench) bench(perm, r);
        System.out.println(fails == 0 ? "TODO OK" : fails + " FALLOS");
        System.exit(fails == 0 ? 0 : 1);
    }

    static void bench(byte[] perm, Random r) {
        System.out.println("\n== BENCH (mediana de 9 rondas, ns por llamada; mismo hilo, JIT caliente) ==");
        for (int bits : new int[] {4, 8, 15}) {
            for (int n : new int[] {256, 4096}) {
                int[] v = new int[n]; for (int i = 0; i < n; i++) v[i] = r.nextInt(1 << bits);
                long[] o = new long[HybridNative.longCount(bits, n)]; int[] back = new int[n];
                final int fb = bits;
                double jv = time(() -> { JavaRef.pack(fb, v, o); JavaRef.unpack(fb, n, o, back); });
                HybridNative.resetForTests(); HybridNative.init(new HybridNative.Settings(true, dirG, true, true, true, 1));
                double nv = time(() -> { HybridNative.packBits(fb, v, o); HybridNative.unpackBits(fb, n, o, back); });
                System.out.printf("bits pack+unpack bits=%2d n=%5d  Java %9.0f ns   Rust/FFM %9.0f ns   x%.2f%n", bits, n, jv, nv, jv / nv);
            }
        }
        for (int n : new int[] {256, 4096, 65536}) {
            double[] xyz = new double[n * 3]; for (int i = 0; i < xyz.length; i++) xyz[i] = r.nextDouble() * 1000;
            double[] o = new double[n];
            double jv = time(() -> JavaRef.noise(perm, xyz, n, o));
            double nv = time(() -> HybridNative.improvedNoiseBatch(perm, xyz, n, o));
            System.out.printf("noise improved   n=%5d          Java %9.0f ns   Rust/FFM %9.0f ns   x%.2f%n", n, jv, nv, jv / nv);
        }
    }
    static String dirG = "natives";
    static double time(Runnable f) {
        for (int i = 0; i < 300; i++) f.run();
        double[] t = new double[9];
        for (int k = 0; k < t.length; k++) {
            int it = 200; long s = System.nanoTime();
            for (int i = 0; i < it; i++) f.run();
            t[k] = (System.nanoTime() - s) / (double) it;
        }
        Arrays.sort(t); return t[4];
    }
}
