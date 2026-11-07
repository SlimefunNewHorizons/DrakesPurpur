package org.purpurmc.purpur.hybrid.lod;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

/**
 * Cache LRU acotada y segura entre hilos para tiles muestreados del canal {@code horizon:lod} v1,
 * con invalidacion O(1) por chunk modificado a traves de todas las escalas ({@code stride = 1..64}).
 * <p>
 * Cada tile de escala {@code stride} cubre {@code size = stride * 16} bloques alineados a multiplos de {@code size}.
 * Como un chunk (16x16 bloques) es divisor exacto de {@code size} para todo {@code stride >= 1}, cualquier cambio
 * de bloque o descarga/regeneracion en {@code (chunkX, chunkZ)} invalida exactamente un tile por escala en O(1).
 */
public final class HorizonLodCache {
    public static final int DEFAULT_MAX_ENTRIES = 2048;
    private static final int[] STRIDES = {1, 2, 4, 8, 16, 32, 64};

    public record TileKey(UUID world, int x, int z, int stride) {}

    public static final LongAdder HITS = new LongAdder();
    public static final LongAdder MISSES = new LongAdder();
    public static final LongAdder PUTS = new LongAdder();
    public static final LongAdder EVICTIONS = new LongAdder();
    public static final LongAdder INVALIDATIONS = new LongAdder();

    private static final HorizonLodCache GLOBAL = new HorizonLodCache(DEFAULT_MAX_ENTRIES);

    private final int maxEntries;
    private final LinkedHashMap<TileKey, List<HorizonLodProtocol.Column>> lru;

    public HorizonLodCache(int maxEntries) {
        this.maxEntries = Math.max(16, maxEntries);
        this.lru = new LinkedHashMap<>(128, 0.75f, true);
    }

    public static HorizonLodCache global() {
        return GLOBAL;
    }

    public synchronized List<HorizonLodProtocol.Column> get(UUID world, int x, int z, int stride) {
        List<HorizonLodProtocol.Column> cols = lru.get(new TileKey(world, x, z, stride));
        if (cols != null) {
            HITS.increment();
            return cols;
        }
        MISSES.increment();
        return null;
    }

    public synchronized void put(UUID world, int x, int z, int stride, List<HorizonLodProtocol.Column> columns) {
        if (columns == null || columns.size() != HorizonLodProtocol.SIDE * HorizonLodProtocol.SIDE) return;
        TileKey key = new TileKey(world, x, z, stride);
        lru.put(key, List.copyOf(columns));
        PUTS.increment();
        while (lru.size() > maxEntries) {
            var it = lru.entrySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
                EVICTIONS.increment();
            } else {
                break;
            }
        }
    }

    /**
     * Invalida en O(1) todos los tiles (strides 1..64) que contienen el chunk {@code (chunkX, chunkZ)}.
     *
     * @return numero de tiles eliminados de la cache
     */
    public synchronized int invalidateChunk(UUID world, int chunkX, int chunkZ) {
        int blockX = chunkX << 4;
        int blockZ = chunkZ << 4;
        int removed = 0;
        for (int stride : STRIDES) {
            int size = stride * HorizonLodProtocol.SIDE;
            int tileX = Math.floorDiv(blockX, size) * size;
            int tileZ = Math.floorDiv(blockZ, size) * size;
            if (lru.remove(new TileKey(world, tileX, tileZ, stride)) != null) {
                removed++;
                INVALIDATIONS.increment();
            }
        }
        return removed;
    }

    public synchronized int invalidateWorld(UUID world) {
        int before = lru.size();
        lru.keySet().removeIf(k -> k.world().equals(world));
        int removed = before - lru.size();
        if (removed > 0) INVALIDATIONS.add(removed);
        return removed;
    }

    public synchronized void clear() {
        lru.clear();
    }

    public synchronized int size() {
        return lru.size();
    }

    public int maxEntries() {
        return maxEntries;
    }

    public String describe() {
        long h = HITS.sum(), m = MISSES.sum();
        double hitRate = (h + m) == 0 ? 0.0 : (100.0 * h / (h + m));
        return String.format("entradas=%d/%d aciertos=%d fallos=%d (%.1f%%) inserciones=%d expulsiones=%d invalidaciones=%d",
            size(), maxEntries, h, m, hitRate, PUTS.sum(), EVICTIONS.sum(), INVALIDATIONS.sum());
    }
}
