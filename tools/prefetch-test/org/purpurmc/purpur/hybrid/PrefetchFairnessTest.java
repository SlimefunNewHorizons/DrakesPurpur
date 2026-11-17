package org.purpurmc.purpur.hybrid;
public class PrefetchFairnessTest {
    static void ok(boolean c, String m) { if (!c) { System.out.println("FAIL " + m); System.exit(1); } }
    public static void main(String[] a) {
        HybridChunkPrefetch.configure(false, 20, 3, 8, 2, 24, 150);
        ok(HybridChunkPrefetch.effectiveMax(0) == 6, "0 volando -> 6");
        ok(HybridChunkPrefetch.effectiveMax(1) == 6, "1 volando -> 6");
        ok(HybridChunkPrefetch.effectiveMax(3) == 18, "3 volando -> 18");
        ok(HybridChunkPrefetch.effectiveMax(50) == 24, "50 volando -> tope 24");
        // equidad: 50 jugadores, 10 libres -> ninguno recibe mas de 1, suma exacta, el resto rota
        int[] q = HybridChunkPrefetch.quotas(10, 50, 0), q2 = HybridChunkPrefetch.quotas(10, 50, 10);
        int s = 0, mx = 0; for (int v : q) { s += v; mx = Math.max(mx, v); }
        ok(s == 10 && mx == 1, "reparto 10/50");
        ok(q[0] == 1 && q2[0] == 0 && q2[10] == 1, "rotacion del desempate");
        int[] r = HybridChunkPrefetch.quotas(24, 5, 3); s = 0; for (int v : r) s += v;
        ok(s == 24 && java.util.Arrays.stream(r).max().getAsInt() - java.util.Arrays.stream(r).min().getAsInt() <= 1, "24/5 equitativo");
        ok(HybridChunkPrefetch.loadFactor(20) == 1.0 && HybridChunkPrefetch.loadFactor(40) == 0.5 && HybridChunkPrefetch.loadFactor(47) == 0.0, "carga MSPT");
        // sin estado: tras 7000 rotaciones con 7 jugadores y 10 libres, cada jugador recibe el mismo total (+-1)
        int[] tot = new int[7]; for (int i = 0; i < 7000; i++) { int[] z = HybridChunkPrefetch.quotas(10, 7, i); for (int j = 0; j < 7; j++) tot[j] += z[j]; }
        int mn = java.util.Arrays.stream(tot).min().getAsInt(), mxx = java.util.Arrays.stream(tot).max().getAsInt();
        ok(mxx - mn <= 1, "equidad a largo plazo " + mn + ".." + mxx);
        System.out.println("PrefetchFairnessTest OK (largo plazo " + mn + ".." + mxx + ")");
    }
}
