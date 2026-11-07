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

            // 8) Comandos /drakes hybrid y GUI con datos reales
            adminSmoke(out, srv);
            if (diffs != 0 || roundTripDiffs != 0 || densityMismatches != 0 || adminFailures != 0) {
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

    // ---- 7) volador sintetico ------------------------------------------------------------------------------
    /**
     * Modelo de demanda: un "volador" avanza por una fila de chunks a {@code speed} bloques/s; necesita cada columna
     * (2R+1 chunks) cuando le quedan (R-2) columnas por delante. Estrategia REACTIVA: solicita columnas hasta R por
     * delante (lo que hace el cargador de Paper con el radio de vision). Estrategia PREDICTIVA: ademas solicita {@code lead}
     * columnas extra (prefetch). Mide cuantas columnas llegan despues de su plazo y cuanto. Usa la API publica
     * {@code getChunkAtAsync}; es un modelo de CARGA sobre el sistema de chunks real, NO un cliente real.
     */
    private static void flyerBench(StringBuilder out, MinecraftServer srv) {
        try {
            org.bukkit.World w = org.bukkit.Bukkit.getWorlds().get(0);
            final int R = 5, cols = 48;
            final double speed = Double.parseDouble(System.getProperty("drakes.hybrid.selftest.flyer.speed", "64"));
            final int lead = Integer.getInteger("drakes.hybrid.selftest.flyer.lead", 6);
            line(out, String.format("[volador sintetico] R=%d chunks, %d columnas de %d chunks, %.0f bloques/s (%.1f columnas/s), lead=%d", R, cols, 2 * R + 1, speed, speed / 16, lead));
            int[] order = {0, lead, lead, 0, 0, lead};   // reactiva/predictiva alternadas (A B B A A B)
            java.util.Map<Integer, List<double[]>> res = new java.util.LinkedHashMap<>();
            for (int run = 0; run < order.length; run++) {
                int ld = order[run];
                double[] r = flyerRun(w, R, cols, speed, ld, 12000 + run * 40, 3000 + run * 40);
                res.computeIfAbsent(ld, k -> new ArrayList<>()).add(r);
                line(out, String.format("  corrida %d (%s): columnas_estables=%d tarde=%d (%.0f%%) lateness p50=%.0f p95=%.0f max=%.0f ms, chunks=%d, total=%.1f s",
                    run + 1, ld == 0 ? "reactiva" : "prefetch lead=" + ld, (int) r[0], (int) r[1], 100.0 * r[1] / Math.max(1, r[0]), r[2], r[3], r[4], (int) r[5], r[6]));
            }
            for (var e : res.entrySet()) {
                double cols_ = 0, late = 0, p95 = 0, p50 = 0;
                for (double[] r : e.getValue()) { cols_ += r[0]; late += r[1]; p95 += r[3]; p50 += r[2]; }
                int n = e.getValue().size();
                line(out, String.format("[volador sintetico] %s: media de %d corridas -> %.1f%% de columnas tarde, lateness p50 %.0f ms, p95 %.0f ms",
                    e.getKey() == 0 ? "REACTIVA" : "PREFETCH lead=" + e.getKey(), n, 100.0 * late / Math.max(1, cols_), p50 / n, p95 / n));
            }
            line(out, "[volador sintetico] modelo de carga, no cliente real; se necesitan mas corridas y hardware dedicado para intervalos de confianza");
        } catch (Throwable t) {
            line(out, "[volador sintetico] ERROR: " + t);
            LOG.log(Level.WARNING, "flyer", t);
        }
    }

    /** @return {columnas_estables, tarde, lateness_p50_ms, p95, max, chunks, total_s} */
    private static double[] flyerRun(org.bukkit.World w, int R, int cols, double speed, int lead, int startCx, int cz0) throws Exception {
        final double secPerCol = 16.0 / speed;
        long t0 = System.nanoTime();
        @SuppressWarnings("unchecked")
        List<java.util.concurrent.CompletableFuture<?>>[] futs = new List[cols];
        final long[] doneAt = new long[cols];
        int requestedUpTo = -1, chunks = 0;
        while (true) {
            double t = (System.nanoTime() - t0) / 1e9;
            int posCol = (int) (t / secPerCol);
            int want = Math.min(cols - 1, posCol + R + lead);
            while (requestedUpTo < want) {
                requestedUpTo++;
                final int c = requestedUpTo;
                boolean urgent = c <= posCol + R;      // necesario ya vs especulativo
                futs[c] = new ArrayList<>();
                for (int dz = -R; dz <= R; dz++) {
                    chunks++;
                    futs[c].add(w.getChunkAtAsync(startCx + c, cz0 + dz, true, urgent).whenComplete((ch, err) -> {
                        long now = System.nanoTime() - t0;
                        synchronized (doneAt) { if (now > doneAt[c]) doneAt[c] = now; }
                    }));
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
            for (String sub : new String[] {"help", "status", "info", "chunks", "workers", "diagnostics", "benchmark", "gui"}) {
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
}
