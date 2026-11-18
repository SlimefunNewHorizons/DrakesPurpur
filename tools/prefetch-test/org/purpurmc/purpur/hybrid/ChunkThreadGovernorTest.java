package org.purpurmc.purpur.hybrid;

public class ChunkThreadGovernorTest {
    static void ok(boolean c, String m) { if (!c) { System.out.println("FAIL " + m); System.exit(1); } }
    public static void main(String[] a) {
        var p = new ChunkThreadGovernor.Params(2, 6, 38, 46, 0.88, 0.95, 4000, 1000);
        ok(ChunkThreadGovernor.autoMax(8, 2, 2) == 6, "8 CPUs - 2 reservados = 6");
        ok(ChunkThreadGovernor.autoMax(4, 2, 2) == 2, "4 CPUs: nunca menos que min");
        ok(ChunkThreadGovernor.autoMax(2, 5, 2) == 2, "reserva enorme: min");
        ok(ChunkThreadGovernor.decide(-1, p, 20, 0.2, 0) == 6, "primera decision sana -> tope");
        ok(ChunkThreadGovernor.decide(-1, p, 40, 0.2, 0) == 2, "primera decision con carga -> minimo");
        ok(ChunkThreadGovernor.decide(6, p, 20, 0.3, 99999) == 6, "sano en el tope: se queda");
        ok(ChunkThreadGovernor.decide(6, p, 40, 0.3, 2000) == 5, "MSPT alto: baja uno");
        ok(ChunkThreadGovernor.decide(6, p, 40, 0.3, 500) == 6, "MSPT alto pero paso reciente: espera");
        ok(ChunkThreadGovernor.decide(6, p, 20, 0.90, 2000) == 5, "CPU alta: baja uno");
        ok(ChunkThreadGovernor.decide(6, p, 47, 0.3, 0) == 2, "tick en peligro: minimo de golpe");
        ok(ChunkThreadGovernor.decide(6, p, 20, 0.96, 0) == 2, "CPU critica: minimo de golpe");
        ok(ChunkThreadGovernor.decide(2, p, 20, 0.3, 5000) == 3, "recuperado: sube uno");
        ok(ChunkThreadGovernor.decide(2, p, 20, 0.3, 1000) == 2, "recuperado pero paso reciente: espera");
        ok(ChunkThreadGovernor.decide(3, p, 34, 0.3, 9000) == 3, "zona de histeresis (34 > 38*0.85): ni sube ni baja");
        ok(ChunkThreadGovernor.decide(3, p, 20, 0.80, 9000) == 3, "CPU 0.80 > 0.78: sin subir");
        ok(ChunkThreadGovernor.decide(2, p, 40, 0.3, 9000) == 2, "no baja del minimo");
        // simulacion: 60 s de carga y luego calma -> baja escalonado y vuelve a subir al tope sin oscilar
        int cur = 6; long since = 99999; int minSeen = cur, flips = 0; int prev = cur;
        for (int t = 0; t < 200; t++) {
            double mspt = (t >= 10 && t < 20) ? 41 : 18; since += 1000;
            int n = ChunkThreadGovernor.decide(cur, p, mspt, 0.4, since);
            if (n != cur) { since = 0; if ((n > cur) != (cur > prev)) flips++; prev = cur; cur = n; }
            minSeen = Math.min(minSeen, cur);
        }
        ok(minSeen == 2 && cur == 6, "baja a 2 durante la carga y vuelve a 6 (min=" + minSeen + " fin=" + cur + ")");
        System.out.println("ChunkThreadGovernorTest OK (minimo visto " + minSeen + ", final " + cur + ")");
    }
}
