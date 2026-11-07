package org.purpurmc.purpur.hybrid;

import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import org.purpurmc.purpur.nativelib.HybridNative;

/**
 * Puntos de enganche del Hybrid Engine en el pipeline de chunks. Llamados desde {@code PalettedContainer}
 * (guardado/carga de secciones). Cada metodo produce EXACTAMENTE lo que produciria el codigo original de Mojang:
 * si el motor esta apagado, el lote es pequeno o Rust falla, ejecuta el codigo original.
 * <p>
 * Seguridad: operan solo sobre arrays que el llamador posee en ese momento (el {@code int[]} recien creado por
 * {@code reencodeContents}, o el {@code long[]} crudo de un {@code SimpleBitStorage} dentro de {@code acquire()/release()}
 * de PalettedContainer); no guardan referencias ni cambian estado del mundo.
 */
public final class HybridChunkOps {
    private HybridChunkOps() {}

    /** Equivale a {@code new SimpleBitStorage(bits, size, values).getRaw()}. */
    public static long[] packRaw(int bits, int size, int[] values) {
        if (values.length == size && HybridNative.bitsActive(size)) {
            final boolean st = HybridStats.enabled;   // UNA sola lectura por llamada (ver nota en HybridStats)
            long t = st ? System.nanoTime() : 0L;
            long[] out = new long[HybridNative.longCount(bits, size)];
            try {
                HybridNative.packBits(bits, values, out);
                if (st) HybridStats.PACK_NATIVE.add(t, size);
                return out;
            } catch (RuntimeException e) {
                // HybridNative ya cayo a Java por dentro ante fallos nativos; esto solo cubre argumentos invalidos:
                // dejamos que el codigo original de Mojang produzca su propio resultado/excepcion.
            }
        }
        final boolean st = HybridStats.enabled;
        long t = st ? System.nanoTime() : 0L;
        long[] raw = new SimpleBitStorage(bits, size, values).getRaw();
        if (st) HybridStats.PACK_JAVA.add(t, size);
        return raw;
    }

    /** Equivale a {@code storage.unpack(output)}. */
    public static void unpackInto(BitStorage storage, int[] output) {
        if (storage instanceof SimpleBitStorage simple && output.length >= simple.getSize()
            && HybridNative.bitsActive(simple.getSize())) {
            final boolean st = HybridStats.enabled;
            long t = st ? System.nanoTime() : 0L;
            HybridNative.unpackBits(simple.getBits(), simple.getSize(), simple.getRaw(), output);
            if (st) HybridStats.UNPACK_NATIVE.add(t, simple.getSize());
            return;
        }
        final boolean st = HybridStats.enabled;
        long t = st ? System.nanoTime() : 0L;
        storage.unpack(output);
        if (st) HybridStats.UNPACK_JAVA.add(t, storage.getSize());
    }
}
