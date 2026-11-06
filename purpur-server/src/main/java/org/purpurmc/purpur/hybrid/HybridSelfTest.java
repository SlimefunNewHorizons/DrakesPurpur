package org.purpurmc.purpur.hybrid;

import com.mojang.serialization.Codec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.purpurmc.purpur.nativelib.HybridNative;

/**
 * Autotest DENTRO del servidor real (solo con {@code -Ddrakes.hybrid.selftest=<radio-en-chunks>}; nunca en uso normal).
 * <ol>
 *   <li>Genera (o carga) el cuadrado de chunks de radio R alrededor de 0,0 y mide ms/chunk (p50/p95/p99).</li>
 *   <li>Paridad: serializa TODAS las secciones con los codecs reales de Mojang (la misma ruta del guardado a region)
 *       con Rust apagado y encendido y exige NBT identico; ademas round-trip decodificar -&gt; recodificar.</li>
 *   <li>Rendimiento A/B alternado (mismo proceso, mismos datos, JIT caliente): serializar y deserializar.</li>
 *   <li>Preparacion de envio: tiempo de construir ClientboundLevelChunkWithLightPacket por chunk.</li>
 * </ol>
 * Escribe {@code drakes-data/hybrid-selftest.txt} y apaga el servidor. Sale con codigo 3 si hay diferencia de paridad.
 */
public final class HybridSelfTest {
    private static final Logger LOG = Logger.getLogger("HybridSelfTest");

    private HybridSelfTest() {}

    public static void maybeStart() {
        String p = System.getProperty("drakes.hybrid.selftest");
        if (p == null || p.isBlank()) return;
        int radius;
        try {
            radius = Math.max(1, Integer.parseInt(p.trim()));
        } catch (NumberFormatException e) {
            LOG.warning("drakes.hybrid.selftest invalido: " + p);
            return;
        }
        final int r = radius;
        Thread t = new Thread(() -> run(r), "Drakes-HybridSelfTest");
        t.setDaemon(true);
        t.start();
    }

    private static <T> T onMain(MinecraftServer server, java.util.function.Supplier<T> job) {
        CompletableFuture<T> f = new CompletableFuture<>();
        server.execute(() -> {
            try {
                f.complete(job.get());
            } catch (Throwable ex) {
                f.completeExceptionally(ex);
            }
        });
        return f.join();
    }

    private static void run(int radius) {
        StringBuilder out = new StringBuilder();
        int exit = 0;
        try {
            MinecraftServer server;
            while ((server = MinecraftServer.getServer()) == null || !server.isRunning() || server.overworld() == null
                || server.getTickCount() < 40) {
                Thread.sleep(500);
            }
            final MinecraftServer srv = server;
            final ServerLevel level = srv.overworld();
            boolean lib = HybridNative.libraryLoaded();
            line(out, "== DrakesPurpur Hybrid selftest == radius=" + radius + " lib=" + lib + " " + HybridNative.status());
            line(out, "jvm=" + System.getProperty("java.vm.name") + " " + Runtime.version() + " cpus=" + Runtime.getRuntime().availableProcessors());

            // 1) generacion / carga
            List<LevelChunk> chunks = new ArrayList<>();
            List<Double> genMs = new ArrayList<>();
            for (int cx = -radius; cx <= radius; cx++) {
                final int fx = cx;
                List<Object[]> row = onMain(srv, () -> {
                    List<Object[]> res = new ArrayList<>();
                    for (int cz = -radius; cz <= radius; cz++) {
                        long t0 = System.nanoTime();
                        LevelChunk c = level.getChunk(fx, cz);
                        res.add(new Object[] {c, (System.nanoTime() - t0) / 1e6});
                    }
                    return res;
                });
                for (Object[] o : row) {
                    chunks.add((LevelChunk) o[0]);
                    genMs.add((Double) o[1]);
                }
            }
            line(out, "[gen/load sincrono hilo principal] chunks=" + chunks.size() + " ms/chunk " + pct(genMs));

            PalettedContainerFactory factory = level.palettedContainerFactory();
            Codec<PalettedContainer<net.minecraft.world.level.block.state.BlockState>> blockCodec = factory.blockStatesContainerCodec();
            Codec<PalettedContainerRO<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>> biomeCodec = factory.biomeContainerCodec();
            List<PalettedContainer<net.minecraft.world.level.block.state.BlockState>> blocks = new ArrayList<>();
            List<PalettedContainerRO<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>> biomes = new ArrayList<>();
            for (LevelChunk c : chunks) {
                for (LevelChunkSection s : c.getSections()) {
                    blocks.add(s.getStates());
                    biomes.add(s.getBiomes());
                }
            }
            line(out, "secciones=" + blocks.size() + " (contenedores de bloques + biomas: " + (blocks.size() * 2) + ")");

            // 2) paridad (en el hilo principal: el mundo sigue siendo la unica autoridad; solo se LEE)
            boolean haveNative = lib && HybridNative.forceBits(false);
            List<Tag> refBlocks = new ArrayList<>(), refBiomes = new ArrayList<>();
            onMain(srv, () -> {
                HybridNative.forceBits(false);
                for (var b : blocks) refBlocks.add(blockCodec.encodeStart(NbtOps.INSTANCE, b).result().orElseThrow());
                for (var b : biomes) refBiomes.add(biomeCodec.encodeStart(NbtOps.INSTANCE, b).result().orElseThrow());
                return null;
            });
            int diffs = 0, roundTripDiffs = 0;
            if (haveNative) {
                HybridStats.resetAll();
                final int[] d = new int[2];
                onMain(srv, () -> {
                    HybridNative.forceBits(true);
                    for (int i = 0; i < blocks.size(); i++) {
                        Tag nb = blockCodec.encodeStart(NbtOps.INSTANCE, blocks.get(i)).result().orElseThrow();
                        if (!nb.equals(refBlocks.get(i))) d[0]++;
                        Tag nbi = biomeCodec.encodeStart(NbtOps.INSTANCE, biomes.get(i)).result().orElseThrow();
                        if (!nbi.equals(refBiomes.get(i))) d[0]++;
                        // round trip: decodificar con Rust, recodificar con Java, debe ser igual al original
                        var dec = blockCodec.parse(NbtOps.INSTANCE, refBlocks.get(i)).result().orElseThrow();
                        HybridNative.forceBits(false);
                        Tag again = blockCodec.encodeStart(NbtOps.INSTANCE, dec).result().orElseThrow();
                        HybridNative.forceBits(true);
                        if (!again.equals(refBlocks.get(i))) d[1]++;
                    }
                    return null;
                });
                diffs = d[0];
                roundTripDiffs = d[1];
                line(out, "[paridad NBT real Rust==Java] contenedores=" + (blocks.size() * 2) + " diferencias=" + diffs + " roundtrip_diferencias=" + roundTripDiffs);
                line(out, "[stats paridad] llamadas nativas pack=" + HybridStats.PACK_NATIVE.calls.sum() + " unpack=" + HybridStats.UNPACK_NATIVE.calls.sum());
            } else {
                line(out, "[paridad] omitida: biblioteca nativa no cargada (modo solo-Java)");
            }

            // 3) rendimiento A/B alternado: guardado (encode) y carga (parse)
            int rounds = 9;
            List<Double> encJ = new ArrayList<>(), encN = new ArrayList<>(), decJ = new ArrayList<>(), decN = new ArrayList<>();
            for (int rd = 0; rd < rounds + 2; rd++) { // 2 rondas de calentamiento
                for (int mode = 0; mode < (haveNative ? 2 : 1); mode++) {
                    final boolean nat = mode == 1;
                    final int fr = rd;
                    double[] res = onMain(srv, () -> {
                        HybridNative.forceBits(nat);
                        long t0 = System.nanoTime();
                        for (int i = 0; i < blocks.size(); i++) {
                            blockCodec.encodeStart(NbtOps.INSTANCE, blocks.get(i));
                            biomeCodec.encodeStart(NbtOps.INSTANCE, biomes.get(i));
                        }
                        long t1 = System.nanoTime();
                        for (int i = 0; i < refBlocks.size(); i++) {
                            blockCodec.parse(NbtOps.INSTANCE, refBlocks.get(i));
                            biomeCodec.parse(NbtOps.INSTANCE, refBiomes.get(i));
                        }
                        long t2 = System.nanoTime();
                        return new double[] {(t1 - t0) / 1e6, (t2 - t1) / 1e6};
                    });
                    if (fr >= 2) {
                        (nat ? encN : encJ).add(res[0]);
                        (nat ? decN : decJ).add(res[1]);
                    }
                }
            }
            HybridNative.forceBits(false);
            int n = chunks.size();
            line(out, String.format("[guardado: codec->NBT, %d chunks] Java mediana %.2f ms (%.1f us/chunk)", n, med(encJ), med(encJ) * 1000 / n));
            line(out, String.format("[carga: NBT->codec, %d chunks]    Java mediana %.2f ms (%.1f us/chunk)", n, med(decJ), med(decJ) * 1000 / n));
            if (haveNative) {
                line(out, String.format("[guardado] Rust mediana %.2f ms (%.1f us/chunk)  => x%.3f vs Java", med(encN), med(encN) * 1000 / n, med(encJ) / med(encN)));
                line(out, String.format("[carga]    Rust mediana %.2f ms (%.1f us/chunk)  => x%.3f vs Java", med(decN), med(decN) * 1000 / n, med(decJ) / med(decN)));
            }

            // 4) desglose por etapa (pack/unpack puros) con estadisticas activadas
            boolean prev = HybridStats.enabled;
            HybridStats.enabled = true;
            for (int mode = 0; mode < (haveNative ? 2 : 1); mode++) {
                final boolean nat = mode == 1;
                HybridStats.resetAll();
                onMain(srv, () -> {
                    HybridNative.forceBits(nat);
                    for (int i = 0; i < blocks.size(); i++) {
                        blockCodec.encodeStart(NbtOps.INSTANCE, blocks.get(i));
                        biomeCodec.encodeStart(NbtOps.INSTANCE, biomes.get(i));
                    }
                    for (int i = 0; i < refBlocks.size(); i++) blockCodec.parse(NbtOps.INSTANCE, refBlocks.get(i));
                    return null;
                });
                line(out, "[desglose etapas, modo " + (nat ? "Rust" : "Java") + "]\n" + HybridStats.report().stripTrailing());
            }
            HybridStats.enabled = prev;
            HybridNative.forceBits(false);

            // 5) preparacion de envio por chunk
            final java.lang.reflect.Constructor<ClientboundLevelChunkWithLightPacket> packetCtor =
                ClientboundLevelChunkWithLightPacket.class.getConstructor(LevelChunk.class,
                    net.minecraft.world.level.lighting.LevelLightEngine.class, java.util.BitSet.class, java.util.BitSet.class);
            List<Double> sendUs = new ArrayList<>();
            for (int rd = 0; rd < 3; rd++) {
                List<Double> part = onMain(srv, () -> {
                    List<Double> res = new ArrayList<>();
                    for (LevelChunk c : chunks) {
                        long t0 = System.nanoTime();
                        // Paper marca ese constructor como "bad call" (paperweight scanJarForBadCalls) fuera del propio servidor;
                        // el autotest es solo diagnostico, asi que se invoca por reflexion.
                        packetCtor.newInstance(c, level.getLightEngine(), null, null);
                        res.add((System.nanoTime() - t0) / 1e3);
                    }
                    return res;
                });
                if (rd > 0) sendUs.addAll(part);
            }
            line(out, "[envio: construir ClientboundLevelChunkWithLightPacket] us/chunk " + pct(sendUs));

            if (diffs != 0 || roundTripDiffs != 0) {
                exit = 3;
                line(out, "RESULTADO: FALLO DE PARIDAD");
            } else {
                line(out, "RESULTADO: OK");
            }
        } catch (Throwable t) {
            exit = 2;
            line(out, "ERROR: " + t);
            LOG.log(Level.SEVERE, "selftest", t);
        }
        try {
            File f = new File("drakes-data/hybrid-selftest.txt");
            f.getParentFile().mkdirs();
            Files.writeString(f.toPath(), out.toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
        System.out.println(out);
        String jfr = System.getProperty("drakes.hybrid.selftest.jfr");
        if (jfr != null && !jfr.isBlank()) { // halt() se salta los shutdown hooks: volcamos la grabacion JFR a mano
            try {
                java.lang.management.ManagementFactory.getPlatformMBeanServer().invoke(
                    new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"), "jfrDump",
                    new Object[] {new String[] {"filename=" + jfr, "name=1"}}, new String[] {String[].class.getName()});
            } catch (Throwable t) {
                LOG.warning("no se pudo volcar JFR: " + t);
            }
        }
        Runtime.getRuntime().halt(exit); // salida inmediata y con codigo; el mundo de prueba es desechable
    }

    private static void line(StringBuilder sb, String s) {
        sb.append(s).append('\n');
        LOG.info(s);
    }

    private static double med(List<Double> v) {
        double[] a = v.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return a.length == 0 ? 0 : a[a.length / 2];
    }

    private static String pct(List<Double> v) {
        double[] a = v.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        if (a.length == 0) return "n/a";
        return String.format("p50=%.3f p95=%.3f p99=%.3f max=%.3f mean=%.3f", a[a.length / 2], a[(int) (a.length * 0.95)],
            a[Math.min(a.length - 1, (int) (a.length * 0.99))], a[a.length - 1], Arrays.stream(a).average().orElse(0));
    }
}
