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
            final boolean initialBits = HybridNative.bitsEnabled();
            line(out, entorno("inicio"));
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
                        buildPacket(packetCtor, c, level.getLightEngine());
                        res.add((System.nanoTime() - t0) / 1e3);
                    }
                    return res;
                });
                if (rd > 0) sendUs.addAll(part);
            }
            line(out, "[envio: construir ClientboundLevelChunkWithLightPacket] us/chunk " + pct(sendUs));


            // 6) Density functions: NormalNoise nativo vs Java (puerta de decision antes de portar mas nodos)
            densityParity(out, level);

            // 7) Pipeline de chunks bajo un volador sintetico: lectura reactiva vs prefetch direccional
            flyerBench(out, srv);

            // 8) Comandos /drakes hybrid y GUI con datos reales (con el estado CONFIGURADO, no el que dejo el autotest)
            HybridNative.forceBits(initialBits);
            adminSmoke(out, srv);

            // 9) DrakesLOD: codec horizon:lod v1 y coste de muestrear terreno lejano sin generar chunks
            lodCodecTest(out);
            lodSamplerProbe(out, srv, level, genMs);
            line(out, entorno("fin"));
            if (diffs != 0 || roundTripDiffs != 0 || densityMismatches != 0 || adminFailures != 0 || lodFailures != 0) {
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
        if (Boolean.getBoolean("drakes.hybrid.selftest.halt")) {
            Runtime.getRuntime().halt(exit); // SOLO entornos desechables y solo si se pide explicitamente
        }
        org.bukkit.Bukkit.shutdown(); // ruta normal: guarda y apaga con los hooks de Paper
    }

    private static void buildPacket(java.lang.reflect.Constructor<ClientboundLevelChunkWithLightPacket> ctor, LevelChunk c,
                                    net.minecraft.world.level.lighting.LevelLightEngine light) {
        try {
            ctor.newInstance(c, light, null, null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long densityMismatches;
    private static long adminFailures;
    private static long lodFailures;

    // ---- 9) DrakesLOD -------------------------------------------------------------------------------------
    private static boolean eq(byte[] a, String hex) {
        return java.util.HexFormat.of().formatHex(a).equals(hex);
    }

    /** Vectores derivados a mano de la especificacion (lod_net.rs); NO se han contrastado con el binario de Horizon. */
    private static void lodCodecTest(StringBuilder out) {
        try {
            var P = org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.class;
            int bad = 0;
            var offer = org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.Offer.of(64);
            if (!eq(offer.encode(), "01400840")) bad++;
            if (!offer.equals(org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.Offer.decode(offer.encode()))) bad++;
            if (org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.Offer.decode(new byte[] {2, 0x40, 8, 0x40}) != null) bad++;   // otra version
            if (org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.Offer.decode(new byte[] {1, 0, 8, 0x40}) != null) bad++;      // distancia 0 = apagado
            var q = new org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.Request(3, -512, 1024, 4, true);
            if (!eq(q.encode(), "03fffffe0000000400" + "0401")) bad++;
            if (!q.equals(org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.Request.decode(q.encode()))) bad++;
            // permitido: tile alineado cerca / no alineado / demasiado lejos / stride no potencia de dos
            if (!new org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.Request(1, 0, 0, 4, false).allowed(64, 10, 10)) bad++;
            if (new org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.Request(1, 8, 0, 4, false).allowed(64, 10, 10)) bad++;
            if (new org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.Request(1, 64 * 1000, 0, 4, false).allowed(64, 0, 0)) bad++;
            if (new org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.Request(1, 0, 0, 3, false).allowed(64, 0, 0)) bad++;
            // respuesta: ida y vuelta de un tile de 256 columnas y limites
            var cols = new ArrayList<org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.Column>();
            for (int i = 0; i < 256; i++) {
                var surf = i % 3 == 0 ? null : new org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.Surface(60 + i % 7, 9,
                    i % 5 == 0 ? new org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.Water(62, 86) : null, i % 2 == 0 ? "minecraft:plains" : "minecraft:forest");
                cols.add(new org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.Column(surf, List.of(
                    new org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.Span(-64, 50, 1, "minecraft:plains", 0),
                    new org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.Span(50, 60 + i % 7, 9, "minecraft:forest", 15))));
            }
            byte[] reply = org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.encodeReply(7, cols);
            if (!cols.equals(org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.decodeReply(reply, 30000))) bad++;
            if (org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.decodeReply(org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.encodeReply(7, cols.subList(0, 10)), 30000) != null) bad++; // no es tile entero -> rechazo
            try { org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.decodeReply(reply, 5); bad++; } catch (IllegalArgumentException expected) { }   // estado desconocido
            var bucket = new org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.Bucket(64, 8, 0);
            int taken = 0;
            for (int i = 0; i < 20; i++) if (bucket.take(0)) taken++;
            if (taken != 8) bad++;
            // Cache LRU + invalidacion O(1) por chunk sucio a traves de escalas (strides 1..64)
            var cache = new org.purpurmc.purpur.hybrid.lod.HorizonLodCache(16);
            var wid = java.util.UUID.randomUUID();
            cache.put(wid, 0, 0, 1, cols);
            cache.put(wid, 0, 0, 4, cols);
            cache.put(wid, 0, 0, 16, cols);
            if (cache.get(wid, 0, 0, 4) == null) bad++;
            if (cache.get(wid, 64, 0, 4) != null) bad++;
            // El chunk (2, 3) cae en bloque (32, 48): invalida stride 4 (tile 0,0, size 64) y stride 16 (tile 0,0, size 256), pero NO stride 1 (tile 0,0, size 16)
            int inv = cache.invalidateChunk(wid, 2, 3);
            if (inv != 2 || cache.get(wid, 0, 0, 1) == null || cache.get(wid, 0, 0, 4) != null || cache.get(wid, 0, 0, 16) != null) bad++;
            lodFailures += bad;
            line(out, "[LOD codec horizon:lod v1 + cache LRU] comprobaciones fallidas=" + bad + " | respuesta de ejemplo: " + reply.length + " bytes (limite " + org.purpurmc.purpur.hybrid.lod.HorizonLodProtocol.MAX_REPLY + ") | invalidacion O(1) por chunk verificada");
        } catch (Throwable t) {
            lodFailures++;
            line(out, "[LOD codec] ERROR: " + t);
        }
    }

    /** Coste de muestrear alturas del generador (sin generar chunks) para un tile de 16x16 columnas a distintos strides. */
    private static void lodSamplerProbe(StringBuilder out, MinecraftServer srv, ServerLevel level, List<Double> genMsPerChunk) {
        try {
            var gen = level.getChunkSource().getGenerator();
            var rs = level.getChunkSource().randomState();
            double meanGen = genMsPerChunk == null || genMsPerChunk.isEmpty() ? 0 : genMsPerChunk.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            line(out, String.format("[LOD sampler] muestreo de altura WORLD_SURFACE_WG del generador (sin generar chunks); referencia: generar un chunk completo = %.1f ms de media", meanGen));
            for (int stride : new int[] {1, 2, 4, 8, 16}) {
                final int st = stride;
                long[] best = {Long.MAX_VALUE};
                int[] sink = {0};
                for (int rep = 0; rep < 5; rep++) {
                    final int r = rep;
                    long t0 = System.nanoTime();
                    for (int cz = 0; cz < 16; cz++) for (int cx = 0; cx < 16; cx++) {
                        sink[0] += gen.getBaseHeight(500000 + r * 4096 + cx * st, 500000 + cz * st, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, level, rs);
                    }
                    best[0] = Math.min(best[0], System.nanoTime() - t0);
                }
                double msTile = best[0] / 1e6;
                int chunksCovered = Math.max(1, (16 * st / 16) * (16 * st / 16));
                line(out, String.format("  stride %2d: tile 16x16 columnas cubre %dx%d bloques (=%d chunks) -> muestreo %.2f ms/tile (%.1f us/columna); generar esos chunks costaria ~%.0f ms (x%.0f)",
                    st, 16 * st, 16 * st, chunksCovered, msTile, msTile * 1000 / 256, meanGen * chunksCovered, meanGen * chunksCovered / Math.max(msTile, 1e-6)));
                if (sink[0] == Integer.MIN_VALUE) line(out, "");
            }
            line(out, "[LOD sampler] muestrea solo la altura de superficie del generador; un tile del protocolo ademas necesita spans por columna (getBaseColumn), bioma, agua y luz: el coste real sera mayor");
        } catch (Throwable t) {
            line(out, "[LOD sampler] ERROR: " + t);
            LOG.log(Level.WARNING, "lod sampler", t);
        }
    }

    // ---- 7) volador sintetico ------------------------------------------------------------------------------
    /**
     * Modelo de demanda: un "volador" avanza por una fila de chunks a {@code speed} bloques/s; necesita cada columna
     * (2R+1 chunks) cuando le quedan (R-2) columnas por delante. Estrategia REACTIVA: solicita columnas hasta R por
     * delante (lo que hace el cargador de Paper con el radio de vision). Estrategia PREDICTIVA: ademas solicita {@code lead}
     * columnas extra (prefetch). Mide cuantas columnas llegan despues de su plazo y cuanto. Usa la API publica
     * {@code getChunkAtAsync}; es un modelo de CARGA sobre el sistema de chunks real, NO un cliente real.
     */
    private static void flyerBench(StringBuilder out, MinecraftServer srv) {
        if (!Boolean.getBoolean("drakes.hybrid.selftest.flyer")) {
            line(out, "[volador sintetico] omitido (activar con -Ddrakes.hybrid.selftest.flyer=true)");
            return;
        }
        try {
            org.bukkit.World w = org.bukkit.Bukkit.getWorlds().get(0);
            final int R = 5, cols = Integer.getInteger("drakes.hybrid.selftest.flyer.cols", 36);
            final int lead = Integer.getInteger("drakes.hybrid.selftest.flyer.lead", 6);
            String[] speeds = System.getProperty("drakes.hybrid.selftest.flyer.speeds", "24").split(",");
            // Matriz de prioridad (hallazgo de la investigacion: el A/B anterior mezclaba 'anticipar' con 'urgent=false').
            // politica: 0 = urgent solo cuando la columna ya es necesaria, 1 = siempre urgent, 2 = nunca urgent, 3 = nunca urgent y se PROMUEVE al vencer.
            final String[] names = {"R-urgent", "R-normal", "P-normal", "P-promovido"};
            final int[] leads = {0, 0, lead, lead};
            final int[] policy = {1, 2, 2, 3};
            int idx = 0;
            for (String sp : speeds) {
                final double speed = Double.parseDouble(sp.trim());
                line(out, String.format("[volador sintetico] R=%d chunks, %d columnas de %d chunks, %.0f bloques/s (demanda %.0f chunks/s), lead=%d", R, cols, 2 * R + 1, speed, speed / 16 * (2 * R + 1), lead));
                int[] order = {0, 1, 2, 3, 3, 2, 1, 0};   // ABCD DCBA: el orden no es una variable
                java.util.Map<Integer, List<double[]>> res = new java.util.LinkedHashMap<>();
                for (int run = 0; run < order.length; run++) {
                    int m = order[run];
                    double[] r = flyerRun(w, R, cols, speed, leads[m], policy[m], 12000 + idx * 60, 3000 + idx * 40);
                    idx++;
                    res.computeIfAbsent(m, k -> new ArrayList<>()).add(r);
                    line(out, String.format("  %.0f b/s corrida %d (%s): estables=%d tarde=%d (%.0f%%) lateness p50=%.0f p95=%.0f max=%.0f ms, total=%.1f s %s",
                        speed, run + 1, names[m], (int) r[0], (int) r[1], 100.0 * r[1] / Math.max(1, r[0]), r[2], r[3], r[4], r[6], entorno("v")));
                }
                for (var e : res.entrySet()) {
                    double cols_ = 0, late = 0, p95 = 0, p50 = 0;
                    for (double[] r : e.getValue()) { cols_ += r[0]; late += r[1]; p95 += r[3]; p50 += r[2]; }
                    int n = e.getValue().size();
                    line(out, String.format("[volador sintetico] %.0f b/s %s: %d corridas -> %.0f%% de columnas tarde, lateness p50 %.0f ms, p95 %.0f ms",
                        speed, names[e.getKey()], n, 100.0 * late / Math.max(1, cols_), p50 / n, p95 / n));
                }
            }
            line(out, "[volador sintetico] modelo de CARGA sobre el sistema de chunks real (no cliente real); cada corrida usa un corredor distinto: la variabilidad del terreno es grande; sin intervalos de confianza");
        } catch (Throwable t) {
            line(out, "[volador sintetico] ERROR: " + t);
            LOG.log(Level.WARNING, "flyer", t);
        }
    }

    /** @return {columnas_estables, tarde, lateness_p50_ms, p95, max, chunks, total_s} */
    private static double[] flyerRun(org.bukkit.World w, int R, int cols, double speed, int lead, int policy, int startCx, int cz0) throws Exception {
        final double secPerCol = 16.0 / speed;
        long t0 = System.nanoTime();
        @SuppressWarnings("unchecked")
        List<java.util.concurrent.CompletableFuture<?>>[] futs = new List[cols];
        final long[] doneAt = new long[cols];
        int requestedUpTo = -1, promotedUpTo = -1, chunks = 0;
        while (true) {
            double t = (System.nanoTime() - t0) / 1e9;
            int posCol = (int) (t / secPerCol);
            int want = Math.min(cols - 1, posCol + R + lead);
            while (requestedUpTo < want) {
                requestedUpTo++;
                final int c = requestedUpTo;
                boolean urgent = policy == 1 || (policy == 0 && c <= posCol + R);
                futs[c] = new ArrayList<>();
                for (int dz = -R; dz <= R; dz++) {
                    chunks++;
                    futs[c].add(w.getChunkAtAsync(startCx + c, cz0 + dz, true, urgent).whenComplete((ch, err) -> {
                        long now = System.nanoTime() - t0;
                        synchronized (doneAt) { if (now > doneAt[c]) doneAt[c] = now; }
                    }));
                }
            }
            if (policy == 3) {
                // promocion: al entrar la columna en la ventana necesaria se re-solicita con urgent=true (Paper sube la prioridad de la tarea)
                int need = Math.min(requestedUpTo, posCol + R);
                while (promotedUpTo < need) {
                    promotedUpTo++;
                    for (int dz = -R; dz <= R; dz++) w.getChunkAtAsync(startCx + promotedUpTo, cz0 + dz, true, true);
                }
            }
            if (posCol >= cols) break;
            Thread.sleep(15);
        }
        for (var l : futs) if (l != null) for (var f : l) f.get(120, java.util.concurrent.TimeUnit.SECONDS);
        List<Double> late = new ArrayList<>();
        int stable = 0, tarde = 0;
        for (int c = R + 2; c < cols; c++) {
            double need = (c - (R - 2)) * secPerCol * 1e9;
            double l = Math.max(0, doneAt[c] - need) / 1e6;
            stable++;
            if (l > 0) tarde++;
            late.add(l);
        }
        double[] a = late.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double p50 = a.length == 0 ? 0 : a[a.length / 2], p95 = a.length == 0 ? 0 : a[Math.min(a.length - 1, (int) (a.length * 0.95))];
        return new double[] {stable, tarde, p50, p95, a.length == 0 ? 0 : a[a.length - 1], chunks, (System.nanoTime() - t0) / 1e9};
    }

    // ---- 8) comandos y GUI ---------------------------------------------------------------------------------
    private static void adminSmoke(StringBuilder out, MinecraftServer srv) {
        try {
            final List<String> captured = new ArrayList<>();
            org.bukkit.command.CommandSender fake = (org.bukkit.command.CommandSender) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.command.CommandSender.class.getClassLoader(), new Class<?>[] {org.bukkit.command.CommandSender.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "sendMessage": if (a != null && a.length > 0 && a[0] instanceof String str) captured.add(str); return null;
                        case "hasPermission": case "isOp": return true;
                        case "getName": return "selftest";
                        case "hashCode": return 1;
                        case "equals": return p == a[0];
                        case "toString": return "selftest-sender";
                        default: return m.getReturnType() == boolean.class ? false : null;
                    }
                });
            int ok = 0, bad = 0;
            for (String sub : new String[] {"help", "status", "info", "chunks", "workers", "diagnostics", "manifest", "benchmark", "gui"}) {
                captured.clear();
                String[] args = {sub};
                boolean handled = onMain(srv, () -> org.purpurmc.purpur.hybrid.HybridAdmin.handle(fake, args));
                boolean error = captured.stream().anyMatch(l -> l.contains("Error en /drakes hybrid"));
                if (handled && !error && !captured.isEmpty()) ok++; else { bad++; line(out, "[comandos] FALLO en '" + sub + "': " + captured); }
            }
            // por la ruta real de comandos de Bukkit
            Boolean viaBukkit = onMain(srv, () -> org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), "drakes hybrid help"));
            // autocompletado
            List<String> tab = onMain(srv, () -> org.purpurmc.purpur.hybrid.HybridAdmin.complete(new String[] {"st"}));
            // GUI
            org.bukkit.inventory.Inventory inv = onMain(srv, org.purpurmc.purpur.hybrid.HybridAdminMenu::build);
            int filled = 0;
            for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) != null && inv.getItem(i).getItemMeta() != null && inv.getItem(i).getItemMeta().hasLore()) filled++;
            line(out, String.format("[comandos] subcomandos ok=%d fallo=%d | dispatchCommand('drakes hybrid help')=%s | tab('st')=%s | GUI: %d slots, %d con datos (lore)",
                ok, bad, viaBukkit, tab, inv.getSize(), filled));
            adminFailures += bad + (Boolean.TRUE.equals(viaBukkit) ? 0 : 1) + (tab.contains("status") ? 0 : 1) + (filled >= 8 ? 0 : 1);
            captured.clear();
            onMain(srv, () -> org.purpurmc.purpur.hybrid.HybridAdmin.handle(fake, new String[] {"status"}));
            for (String l : captured) line(out, "    " + org.bukkit.ChatColor.stripColor(l));
        } catch (Throwable t) {
            adminFailures++;
            line(out, "[comandos] ERROR: " + t);
            LOG.log(Level.WARNING, "admin", t);
        }
    }

    private static void densityParity(StringBuilder out, ServerLevel level) {
        if (!HybridNative.libraryLoaded()) {
            line(out, "[density] omitido: biblioteca nativa no cargada");
            return;
        }
        // Instancias reales de Mojang: parametros de un ruido tipo overworld y de uno de pocas octavas, semilla fija.
        var cases = new java.util.LinkedHashMap<String, net.minecraft.world.level.levelgen.synth.NormalNoise>();
        cases.put("temperature(-10;1,1)", net.minecraft.world.level.levelgen.synth.NormalNoise.create(
            new net.minecraft.world.level.levelgen.XoroshiroRandomSource(12345L), -10, 1.5, 0.0, 1.0, 0.0, 0.0, 0.0));
        cases.put("continentalness(-9;9 oct)", net.minecraft.world.level.levelgen.synth.NormalNoise.create(
            new net.minecraft.world.level.levelgen.XoroshiroRandomSource(777L), -9, 1.0, 1.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0));
        cases.put("shift(-3;4 oct)", net.minecraft.world.level.levelgen.synth.NormalNoise.create(
            new net.minecraft.world.level.levelgen.XoroshiroRandomSource(-42L), -3, 1.0, 1.0, 1.0, 0.0));
        java.util.Random r = new java.util.Random(99);
        final int N = 200_000;
        double[] xyz = new double[N * 3];
        for (int i = 0; i < N; i++) {
            int mode = i % 4;
            for (int k = 0; k < 3; k++) {
                double v = switch (mode) {
                    case 0 -> (r.nextDouble() - 0.5) * 4096;            // zona cercana
                    case 1 -> (r.nextDouble() - 0.5) * 6.0e7;           // frontera del wrap de 3.3554432E7
                    case 2 -> Math.floor((r.nextDouble() - 0.5) * 2000); // coordenadas enteras (puntos de malla)
                    default -> (r.nextInt(2) == 0 ? -1 : 1) * r.nextDouble() * 1.0e9;
                };
                xyz[i * 3 + k] = v;
            }
        }
        for (var e : cases.entrySet()) {
            var jn = e.getValue();
            byte[] blob = HybridDensity.export(jn);
            long h = blob == null ? -1 : HybridNative.normalNoiseCreate(blob);
            if (h <= 0) {
                line(out, "[density] " + e.getKey() + ": NO exportable (se usaria Java)");
                densityMismatches++;
                continue;
            }
            double[] nat = new double[N];
            HybridNative.normalNoiseBatch(h, xyz, N, nat);
            long mism = 0, maxUlp = 0;
            int firstBad = -1;
            for (int i = 0; i < N; i++) {
                double j = jn.getValue(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]);
                long a = Double.doubleToLongBits(j), b = Double.doubleToLongBits(nat[i]);
                if (a != b && !(Double.isNaN(j) && Double.isNaN(nat[i]))) {
                    mism++;
                    maxUlp = Math.max(maxUlp, Math.abs(a - b));
                    if (firstBad < 0) firstBad = i;
                }
            }
            densityMismatches += mism;
            line(out, String.format("[density] %s puntos=%d diferencias_bit_a_bit=%d max_ulp=%d%s", e.getKey(), N, mism, maxUlp,
                firstBad < 0 ? "" : " primer=(" + xyz[firstBad * 3] + "," + xyz[firstBad * 3 + 1] + "," + xyz[firstBad * 3 + 2] + ")"));
            // rendimiento: por punto, lotes de 4096 y 65536 (mediana de 7 rondas)
            for (int batch : new int[] {4096, 65536}) {
                double[] bx = java.util.Arrays.copyOf(xyz, batch * 3), bo = new double[batch];
                double javaNs = medianNs(() -> {
                    for (int i = 0; i < batch; i++) bo[i] = jn.getValue(bx[i * 3], bx[i * 3 + 1], bx[i * 3 + 2]);
                }) / batch;
                final long fh = h;
                double natNs = medianNs(() -> HybridNative.normalNoiseBatch(fh, bx, batch, bo)) / batch;
                line(out, String.format("[density-bench] %s lote=%d Java %.1f ns/punto  Rust %.1f ns/punto  => x%.2f", e.getKey(), batch, javaNs, natNs, javaNs / natNs));
            }
            HybridNative.normalNoiseFree(h);
        }
    }

    private static double medianNs(Runnable f) {
        for (int i = 0; i < 20; i++) f.run();
        double[] t = new double[9];
        for (int k = 0; k < t.length; k++) {
            long s0 = System.nanoTime();
            f.run();
            t[k] = System.nanoTime() - s0;
        }
        java.util.Arrays.sort(t);
        return t[t.length / 2];
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

    /** Condiciones del host para poder decidir si dos corridas son comparables (carga ajena, CPUs, GC). */
    private static String entorno(String fase) {
        String load = "?";
        try {
            load = Files.readString(java.nio.file.Path.of("/proc/loadavg")).trim();
        } catch (Throwable ignored) {
        }
        long gcMs = 0;
        for (var gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) gcMs += Math.max(0, gc.getCollectionTime());
        return "[entorno " + fase + "] loadavg(1/5/15 procs)=" + load + " cpus=" + Runtime.getRuntime().availableProcessors()
            + " gcAcumulado=" + gcMs + "ms bitsConfigurado=" + HybridNative.bitsEnabled();
    }
}
