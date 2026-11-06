package org.purpurmc.purpur.hybrid;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

/**
 * Exportador de {@link NormalNoise} (instancia ya construida por Mojang con su RandomSource/seed/datapack) al formato
 * binario que evalua {@code density.rs}. Java sigue siendo la autoridad de la semilla y de los parametros; si cualquier
 * campo no tiene la forma esperada devuelve {@code null} y el llamador usa la implementacion Java sin cambios.
 * <p>
 * Usa reflexion sobre campos privados de Mojang (valueFactor, first, second, noiseLevels, amplitudes, lowestFreq*, p):
 * es aceptable mientras el modulo sea experimental, pero el paso siguiente es un parche con accesores explicitos para no
 * depender de nombres de campos entre versiones de Paper/Purpur.
 */
public final class HybridDensity {
    private HybridDensity() {}

    private static Field f(Class<?> c, String name) throws ReflectiveOperationException {
        Field fl = c.getDeclaredField(name);
        fl.setAccessible(true);
        return fl;
    }

    /** @return blob para {@code drakes_noise_create}, o null si no es exportable. */
    public static byte[] export(NormalNoise noise) {
        try {
            double valueFactor = f(NormalNoise.class, "valueFactor").getDouble(noise);
            PerlinNoise first = (PerlinNoise) f(NormalNoise.class, "first").get(noise);
            PerlinNoise second = (PerlinNoise) f(NormalNoise.class, "second").get(noise);
            ByteBuffer b = ByteBuffer.allocate(1 << 16).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(1).putDouble(valueFactor);
            if (!perlin(b, first) || !perlin(b, second)) return null;
            byte[] out = new byte[b.position()];
            b.flip().get(out);
            return out;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static boolean perlin(ByteBuffer b, PerlinNoise p) throws ReflectiveOperationException {
        ImprovedNoise[] levels = (ImprovedNoise[]) f(PerlinNoise.class, "noiseLevels").get(p);
        DoubleList amps = (DoubleList) f(PerlinNoise.class, "amplitudes").get(p);
        if (levels.length < 1 || levels.length > 64 || amps.size() != levels.length) return false;
        b.putInt(levels.length);
        b.putDouble(f(PerlinNoise.class, "lowestFreqInputFactor").getDouble(p));
        b.putDouble(f(PerlinNoise.class, "lowestFreqValueFactor").getDouble(p));
        Field pf = f(ImprovedNoise.class, "p");
        for (int i = 0; i < levels.length; i++) {
            ImprovedNoise n = levels[i];
            if (n == null) {
                b.put((byte) 0);
                continue;
            }
            byte[] perm = (byte[]) pf.get(n);
            if (perm.length != 256) return false;
            b.put((byte) 1).putDouble(amps.getDouble(i)).putDouble(n.xo).putDouble(n.yo).putDouble(n.zo).put(perm);
        }
        return true;
    }
}
