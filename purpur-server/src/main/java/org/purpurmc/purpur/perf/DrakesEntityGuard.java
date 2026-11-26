package org.purpurmc.purpur.perf;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.purpurmc.purpur.nativelib.HybridNative;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * DrakesGuard — control automático de entidades y mitigación de bajones de TPS.
 * <p>
 * Usa el núcleo Rust {@code drakes_entity_guard_analyze} en {@code libdrakes_hybrid_ffi.so} (Panama FFM)
 * con respaldo idéntico en Java puro. Nunca elimina entidades protegidas ni ItemFrames.
 */
public final class DrakesEntityGuard {
    private static final Logger LOG = Logger.getLogger("DrakesGuard");

    public static final byte KIND_OTHER = 0;
    public static final byte KIND_MOB = 1;
    public static final byte KIND_DISPOSABLE = 2;
    public static final byte KIND_ITEM_FRAME = 3;

    public static final byte ACTION_KEEP = 0;
    public static final byte ACTION_THROTTLE_AI = 1;
    public static final byte ACTION_CLEAN_DISPOSABLE = 2;

    private static volatile MethodHandle nativeAnalyze;
    private static volatile boolean nativeProbed;

    private static long highSinceMs = -1;
    private static long lastActionMs = 0;
    private static final Set<UUID> THROTTLED_MOBS = ConcurrentHashMap.newKeySet();

    private DrakesEntityGuard() {}

    /** Carga perezosa del símbolo Rust drakes_entity_guard_analyze desde libdrakes_hybrid_ffi.so. */
    private static MethodHandle probeNative() {
        if (nativeProbed) return nativeAnalyze;
        nativeProbed = true;
        try {
            String libPath = HybridNative.libraryPath();
            if (libPath == null || libPath.isBlank()) return null;
            SymbolLookup lookup = SymbolLookup.libraryLookup(Path.of(libPath), Arena.global());
            var sym = lookup.find("drakes_entity_guard_analyze");
            if (sym.isEmpty()) return null;
            FunctionDescriptor desc = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT
            );
            nativeAnalyze = Linker.nativeLinker().downcallHandle(sym.get(), desc);
            LOG.info("[DrakesGuard] Núcleo Rust drakes_entity_guard_analyze activo (" + libPath + ")");
        } catch (Throwable t) {
            LOG.warning("[DrakesGuard] Fallback Java puro (sin símbolo Rust): " + t.getMessage());
            nativeAnalyze = null;
        }
        return nativeAnalyze;
    }

    /** Análisis híbrido (Rust FFM con respaldo Java idéntico). Devuelve número de hotspots escritos en outHotspots (6 ints c/u). */
    public static int analyze(
        int[] chunkX, int[] chunkZ, byte[] kind, byte[] prot,
        int mobThreshold, int itemThreshold, int frameThreshold,
        byte[] outActions, int[] outHotspots
    ) {
        int n = chunkX.length;
        if (n == 0) return 0;
        int maxHotspots = outHotspots.length / 6;
        MethodHandle mh = probeNative();
        if (mh != null) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment sCx = arena.allocateFrom(ValueLayout.JAVA_INT, chunkX);
                MemorySegment sCz = arena.allocateFrom(ValueLayout.JAVA_INT, chunkZ);
                MemorySegment sKd = arena.allocateFrom(ValueLayout.JAVA_BYTE, kind);
                MemorySegment sPr = arena.allocateFrom(ValueLayout.JAVA_BYTE, prot);
                MemorySegment sAct = arena.allocate(n);
                MemorySegment sHs = arena.allocate((long) Math.max(1, maxHotspots) * 6L * 4L, 4);
                int rc = (int) mh.invokeExact(
                    sCx, sCz, sKd, sPr, n,
                    mobThreshold, itemThreshold, frameThreshold,
                    sAct, sHs, maxHotspots
                );
                if (rc >= 0) {
                    MemorySegment.copy(sAct, ValueLayout.JAVA_BYTE, 0, outActions, 0, n);
                    if (rc > 0) {
                        MemorySegment.copy(sHs, ValueLayout.JAVA_INT, 0, outHotspots, 0, rc * 6);
                    }
                    return rc;
                }
            } catch (Throwable t) {
                LOG.warning("[DrakesGuard] Error FFI Rust, usando respaldo Java: " + t.getMessage());
                nativeAnalyze = null;
            }
        }
        return analyzeJava(chunkX, chunkZ, kind, prot, mobThreshold, itemThreshold, frameThreshold, outActions, outHotspots);
    }

    /** Implementación de referencia en Java puro con semántica idéntica a entity_guard.rs. */
    public static int analyzeJava(
        int[] chunkX, int[] chunkZ, byte[] kind, byte[] prot,
        int mobThreshold, int itemThreshold, int frameThreshold,
        byte[] outActions, int[] outHotspots
    ) {
        int n = chunkX.length;
        Map<Long, int[]> chunks = new HashMap<>(); // [total, mobs, disposables, frames]
        for (int i = 0; i < n; i++) {
            long key = (((long) chunkX[i]) << 32) | (chunkZ[i] & 0xFFFFFFFFL);
            int[] st = chunks.computeIfAbsent(key, k -> new int[4]);
            st[0]++;
            if (kind[i] == KIND_MOB) st[1]++;
            else if (kind[i] == KIND_DISPOSABLE && prot[i] == 0) st[2]++;
            else if (kind[i] == KIND_ITEM_FRAME) st[3]++;
        }
        Map<Long, Integer> mobSeen = new HashMap<>();
        for (int i = 0; i < n; i++) {
            outActions[i] = ACTION_KEEP;
            if (prot[i] != 0) continue;
            long key = (((long) chunkX[i]) << 32) | (chunkZ[i] & 0xFFFFFFFFL);
            int[] st = chunks.get(key);
            if (st == null) continue;
            if (kind[i] == KIND_MOB && mobThreshold > 0 && st[1] > mobThreshold) {
                int seen = mobSeen.merge(key, 1, Integer::sum);
                if (seen > mobThreshold) outActions[i] = ACTION_THROTTLE_AI;
            } else if (kind[i] == KIND_DISPOSABLE && itemThreshold > 0 && st[2] > itemThreshold) {
                outActions[i] = ACTION_CLEAN_DISPOSABLE;
            }
        }
        List<int[]> hotspots = new ArrayList<>();
        for (var e : chunks.entrySet()) {
            int cx = (int) (e.getKey() >> 32);
            int cz = (int) (long) e.getKey();
            int[] st = e.getValue();
            int score = (mobThreshold > 0 ? Math.max(0, st[1] - mobThreshold) : 0) * 3
                + (itemThreshold > 0 ? Math.max(0, st[2] - itemThreshold) : 0) * 2
                + (frameThreshold > 0 ? Math.max(0, st[3] - frameThreshold) : 0) * 2;
            if (score > 0) hotspots.add(new int[] {cx, cz, st[0], st[1], st[2], st[3], score});
        }
        hotspots.sort((a, b) -> {
            int c = Integer.compare(b[6], a[6]);
            return c != 0 ? c : Integer.compare(b[2], a[2]);
        });
        int maxRec = outHotspots.length / 6;
        int written = Math.min(hotspots.size(), maxRec);
        for (int i = 0; i < written; i++) {
            System.arraycopy(hotspots.get(i), 0, outHotspots, i * 6, 6);
        }
        return written;
    }

    /** Lista blanca intocable: nunca borrar aldeanos, mascotas, entidades con nombre, ItemFrames, Displays ni PDC de plugins. */
    public static boolean isProtected(Entity e) {
        if (e instanceof Player || e instanceof Villager || e instanceof WanderingTrader
            || e instanceof ArmorStand || e instanceof Display || e instanceof ItemFrame
            || e instanceof Painting || e instanceof LeashHitch
            || e instanceof org.bukkit.entity.minecart.StorageMinecart
            || e instanceof org.bukkit.entity.minecart.HopperMinecart) {
            return true;
        }
        if (e.customName() != null) return true;
        if (e instanceof Tameable t && t.isTamed()) return true;
        if (e instanceof LivingEntity le && le.isLeashed()) return true;
        if (!e.getScoreboardTags().isEmpty()) return true;
        try {
            if (!e.getPersistentDataContainer().getKeys().isEmpty()) return true;
        } catch (Throwable ignored) {}
        if (e instanceof Item item) {
            var stack = item.getItemStack();
            if (stack != null && stack.hasItemMeta()) {
                var meta = stack.getItemMeta();
                if (meta != null && (meta.hasDisplayName() || !meta.getPersistentDataContainer().getKeys().isEmpty())) {
                    return true; // ítems de Slimefun u objetos renombrados: intocables
                }
            }
            if (item.getTicksLived() < 600) return true; // menos de 30s en el suelo
        }
        return false;
    }

    public static byte classify(Entity e) {
        if (e instanceof ItemFrame) return KIND_ITEM_FRAME;
        if (e instanceof Item || e instanceof ExperienceOrb || (e instanceof AbstractArrow a && a.isInBlock())) {
            return KIND_DISPOSABLE;
        }
        if (e instanceof Mob) return KIND_MOB;
        return KIND_OTHER;
    }

    /** Llamado desde DrakesPerf.sample() en el hilo principal. */
    public static void tick(long nowMs, double mspt, YamlConfiguration cfg) {
        if (cfg == null || !cfg.getBoolean("entity-guard.enabled", true)) return;
        double triggerMspt = cfg.getDouble("entity-guard.mspt-trigger", 45.0);
        double recoverMspt = cfg.getDouble("entity-guard.mspt-recover", 32.0);
        long sustainMs = cfg.getLong("entity-guard.sustain-seconds", 10) * 1000L;
        long cooldownMs = cfg.getLong("entity-guard.cooldown-seconds", 60) * 1000L;

        if (mspt < recoverMspt) {
            highSinceMs = -1;
            if (!THROTTLED_MOBS.isEmpty()) restoreThrottledMobs();
            return;
        }
        if (mspt < triggerMspt) {
            highSinceMs = -1;
            return;
        }
        if (highSinceMs < 0) {
            highSinceMs = nowMs;
            return;
        }
        if (nowMs - highSinceMs < sustainMs || nowMs - lastActionMs < cooldownMs) return;

        lastActionMs = nowMs;
        int mobLim = cfg.getInt("entity-guard.chunk-mob-limit", 40);
        int itemLim = cfg.getInt("entity-guard.chunk-disposable-limit", 35);
        int frameLim = cfg.getInt("entity-guard.chunk-itemframe-warn", 48);
        boolean notifyPlayers = cfg.getBoolean("entity-guard.notify-players", true);

        int totalThrottled = 0;
        int totalCleaned = 0;
        String topSummary = null;

        for (World w : Bukkit.getWorlds()) {
            List<Entity> ents = w.getEntities();
            int n = ents.size();
            if (n < Math.min(mobLim, itemLim)) continue;
            int[] cx = new int[n];
            int[] cz = new int[n];
            byte[] kd = new byte[n];
            byte[] pr = new byte[n];
            for (int i = 0; i < n; i++) {
                Entity e = ents.get(i);
                Location loc = e.getLocation();
                cx[i] = loc.getBlockX() >> 4;
                cz[i] = loc.getBlockZ() >> 4;
                kd[i] = classify(e);
                pr[i] = isProtected(e) ? (byte) 1 : (byte) 0;
            }
            byte[] actions = new byte[n];
            int[] hotspots = new int[18]; // top 3 chunks por mundo
            int hsCount = analyze(cx, cz, kd, pr, mobLim, itemLim, frameLim, actions, hotspots);
            if (hsCount <= 0) continue;

            for (int i = 0; i < n; i++) {
                if (actions[i] == ACTION_THROTTLE_AI && ents.get(i) instanceof Mob mob) {
                    if (mob.isAware()) {
                        mob.setAware(false);
                        THROTTLED_MOBS.add(mob.getUniqueId());
                        totalThrottled++;
                    }
                } else if (actions[i] == ACTION_CLEAN_DISPOSABLE) {
                    ents.get(i).remove();
                    totalCleaned++;
                }
            }

            if (topSummary == null) {
                int hcx = hotspots[0], hcz = hotspots[1];
                int hTotal = hotspots[2], hMobs = hotspots[3], hDisp = hotspots[4], hFrames = hotspots[5];
                String near = nearestPlayer(w, (hcx << 4) + 8, (hcz << 4) + 8);
                topSummary = String.format(Locale.ROOT,
                    "%s chunk(%d,%d) [%d,%d] cerca de %s (total=%d, mobs=%d, basura=%d, itemFrames=%d)",
                    w.getName(), hcx, hcz, (hcx << 4) + 8, (hcz << 4) + 8, near, hTotal, hMobs, hDisp, hFrames);
            }
        }

        if (topSummary != null) {
            LOG.warning(String.format(Locale.ROOT,
                "[DrakesGuard] MSPT=%.1f | Foco: %s | Mitigación: IA pausada=%d, basura limpia=%d (0 entidades protegidas ni ItemFrames borrados). TPS estabilizándose.",
                mspt, topSummary, totalThrottled, totalCleaned));
            if (notifyPlayers && (totalThrottled > 0 || totalCleaned > 0)) {
                Component msg = Component.text(
                    "⚡ [DrakesGuard] Se detectó saturación de entidades en una zona y se activó ahorro automático (sin borrar mascotas, aldeanos, ItemFrames ni ítems de Slimefun). Los TPS se estabilizarán en segundos.",
                    NamedTextColor.YELLOW
                );
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendMessage(msg);
                }
            }
        }
    }

    private static void restoreThrottledMobs() {
        int restored = 0;
        Iterator<UUID> it = THROTTLED_MOBS.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            Entity e = Bukkit.getEntity(id);
            if (e instanceof Mob mob && mob.isValid()) {
                mob.setAware(true);
                restored++;
            }
            it.remove();
        }
        if (restored > 0) {
            LOG.info("[DrakesGuard] MSPT recuperado: restaurada IA de " + restored + " mobs.");
        }
    }

    private static String nearestPlayer(World w, int bx, int bz) {
        String best = "ninguno";
        double bestDist = Double.MAX_VALUE;
        for (Player p : w.getPlayers()) {
            double dx = p.getLocation().getX() - bx;
            double dz = p.getLocation().getZ() - bz;
            double d = dx * dx + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                best = p.getName();
            }
        }
        return best;
    }
}
