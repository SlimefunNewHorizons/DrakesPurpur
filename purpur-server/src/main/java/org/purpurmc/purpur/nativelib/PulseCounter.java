package org.purpurmc.purpur.nativelib;

/**
 * Contador de pulsos de redstone por bloque en ventana deslizante (guardia de relojes).
 * Misma semantica en las dos implementaciones (Java y Rust): al registrar un pulso se descartan los
 * anteriores a {@code now - longWindowMs}; {@code fast} cuenta los de {@code ts >= now - fastWindowMs}.
 * Las implementaciones NO son thread-safe salvo que se diga: llamar desde el hilo principal.
 */
public interface PulseCounter {
    /** Devuelve {@code (fast << 32) | long}, o -1 si esta lleno / fallo (el llamador debe dejar pasar). */
    long record(int world, int x, int y, int z, long nowMs, long longWindowMs, long fastWindowMs);

    void remove(int world, int x, int y, int z);

    /** Olvida bloques sin pulsos desde hace mas de idleMs. Devuelve cuantos. */
    int sweep(long nowMs, long idleMs);

    void clear();

    int size();

    static int fast(long packed) {
        return (int) (packed >>> 32);
    }

    static int longCount(long packed) {
        return (int) packed;
    }

    /**
     * Implementacion Java sin asignaciones por pulso: tabla hash abierta de capacidad fija y anillos
     * long[] creados la primera vez que se ve un bloque.
     */
    final class Java implements PulseCounter {
        private final int cap;
        private final int maxEntries;
        private final int mask;
        private final long[] ka, kb;
        private final byte[] state; // 0 vacio, 1 usado, 2 tumba
        private final int[] head, len;
        private final long[][] rings;
        private int used, tombs;

        public Java(int ringCap, int maxEntries) {
            this.cap = Math.max(8, Math.min(1 << 16, ringCap));
            this.maxEntries = Math.max(16, maxEntries);
            int n = Integer.highestOneBit(this.maxEntries * 2 - 1) << 1;
            this.mask = n - 1;
            ka = new long[n];
            kb = new long[n];
            state = new byte[n];
            head = new int[n];
            len = new int[n];
            rings = new long[n][];
        }

        private int hash(long a, long b) {
            long h = (a * 0x9E3779B97F4A7C15L) ^ (b * 0xC2B2AE3D27D4EB4FL);
            h ^= h >>> 32;
            return (int) h & mask;
        }

        private int find(long a, long b) {
            int i = hash(a, b);
            while (state[i] != 0) {
                if (state[i] == 1 && ka[i] == a && kb[i] == b) return i;
                i = (i + 1) & mask;
            }
            return -1;
        }

        @Override
        public long record(int world, int x, int y, int z, long now, long longMs, long fastMs) {
            long a = ((long) world << 32) | (x & 0xFFFFFFFFL);
            long b = ((long) y << 32) | (z & 0xFFFFFFFFL);
            int i = find(a, b);
            if (i < 0) {
                if (used >= maxEntries) return -1;
                if (used + tombs >= (mask + 1) * 3 / 4) rehash();
                i = hash(a, b);
                while (state[i] == 1) i = (i + 1) & mask;
                if (state[i] == 2) tombs--;
                state[i] = 1;
                ka[i] = a;
                kb[i] = b;
                head[i] = 0;
                len[i] = 0;
                if (rings[i] == null) rings[i] = new long[cap];
                used++;
            }
            long[] r = rings[i];
            int h = head[i], n = len[i];
            if (n == cap) {
                r[h] = now;
                h = (h + 1) % cap;
            } else {
                r[(h + n) % cap] = now;
                n++;
            }
            long longCut = now - longMs;
            while (n > 0 && r[h] < longCut) {
                h = (h + 1) % cap;
                n--;
            }
            long fastCut = now - fastMs;
            int fast = 0;
            for (int k = n; k > 0 && r[(h + k - 1) % cap] >= fastCut; k--) fast++;
            head[i] = h;
            len[i] = n;
            return ((long) fast << 32) | (n & 0xFFFFFFFFL);
        }

        private void rehash() {
            int n = mask + 1;
            long[] oa = ka.clone(), ob = kb.clone();
            byte[] os = state.clone();
            int[] oh = head.clone(), ol = len.clone();
            long[][] or = rings.clone();
            java.util.Arrays.fill(state, (byte) 0);
            java.util.Arrays.fill(rings, null);
            used = 0;
            tombs = 0;
            for (int j = 0; j < n; j++) {
                if (os[j] != 1) continue;
                int i = hash(oa[j], ob[j]);
                while (state[i] != 0) i = (i + 1) & mask;
                state[i] = 1;
                ka[i] = oa[j];
                kb[i] = ob[j];
                head[i] = oh[j];
                len[i] = ol[j];
                rings[i] = or[j];
                used++;
            }
        }

        @Override
        public void remove(int world, int x, int y, int z) {
            int i = find(((long) world << 32) | (x & 0xFFFFFFFFL), ((long) y << 32) | (z & 0xFFFFFFFFL));
            if (i >= 0) {
                state[i] = 2; // el anillo se conserva para reutilizarlo (sin asignar)
                used--;
                tombs++;
            }
        }

        @Override
        public int sweep(long now, long idleMs) {
            long cut = now - idleMs;
            int dropped = 0;
            for (int i = 0; i <= mask; i++) {
                if (state[i] != 1) continue;
                long newest = len[i] == 0 ? Long.MIN_VALUE : rings[i][(head[i] + len[i] - 1) % cap];
                if (newest < cut) {
                    state[i] = 2;
                    used--;
                    tombs++;
                    dropped++;
                }
            }
            return dropped;
        }

        @Override
        public void clear() {
            java.util.Arrays.fill(state, (byte) 0);
            used = 0;
            tombs = 0;
        }

        @Override
        public int size() {
            return used;
        }
    }
}
