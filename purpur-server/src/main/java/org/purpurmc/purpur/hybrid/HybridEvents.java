package org.purpurmc.purpur.hybrid;

import java.lang.reflect.Proxy;
import java.util.logging.Logger;
import java.util.concurrent.atomic.LongAdder;
import org.bukkit.Bukkit;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

/**
 * Observabilidad y GUI sin plugin propio: el kernel no es un plugin, asi que los listeners se registran con
 * {@code registerEvent(...)} usando un {@link Plugin} interno minimo. Si el registro falla (otra version de Paper),
 * {@link #ensureRegistered()} devuelve false y las funciones que lo necesitan (GUI, contadores) se desactivan con un
 * mensaje claro; el resto del motor no depende de esto.
 */
public final class HybridEvents {
    private static final Logger LOG = Logger.getLogger("HybridEvents");

    public static final LongAdder CHUNK_LOADS_NEW = new LongAdder();
    public static final LongAdder CHUNK_LOADS_EXISTING = new LongAdder();
    public static final LongAdder CHUNK_SENDS = new LongAdder();

    private static volatile boolean registered;
    private static volatile String error = "";
    private static volatile long registeredAtMs;

    private HybridEvents() {}

    /** Titular de inventario de la GUI hibrida: identifica nuestros inventarios para cancelar clics. */
    public static final class MenuHolder implements InventoryHolder {
        private Inventory inventory;

        public void bind(Inventory inv) {
            this.inventory = inv;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public static boolean registered() {
        return registered;
    }

    public static String error() {
        return error;
    }

    public static long registeredAtMs() {
        return registeredAtMs;
    }

    private static Object defaultFor(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == double.class) return 0.0;
        if (t == float.class) return 0f;
        return null;
    }

    private static Plugin internalPlugin() {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, (proxy, m, a) -> {
            switch (m.getName()) {
                case "isEnabled": return true;
                case "getName": return "DrakesHybrid";
                case "getLogger": return LOG;
                case "toString": return "DrakesHybridInternal";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == a[0];
                default: return defaultFor(m.getReturnType());
            }
        });
    }

    public static synchronized boolean ensureRegistered() {
        if (registered) return true;
        try {
            Plugin plugin = internalPlugin();
            Listener l = new Listener() {};
            var pm = Bukkit.getPluginManager();
            pm.registerEvent(ChunkLoadEvent.class, l, EventPriority.MONITOR, (li, e) -> {
                if (e instanceof ChunkLoadEvent c) (c.isNewChunk() ? CHUNK_LOADS_NEW : CHUNK_LOADS_EXISTING).increment();
            }, plugin);
            pm.registerEvent(io.papermc.paper.event.packet.PlayerChunkLoadEvent.class, l, EventPriority.MONITOR,
                (li, e) -> CHUNK_SENDS.increment(), plugin);
            pm.registerEvent(org.bukkit.event.block.BlockBreakEvent.class, l, EventPriority.MONITOR, (li, e) -> {
                if (e instanceof org.bukkit.event.block.BlockBreakEvent b) {
                    var blk = b.getBlock();
                    org.purpurmc.purpur.hybrid.lod.HorizonLodCache.global().invalidateChunk(blk.getWorld().getUID(), blk.getX() >> 4, blk.getZ() >> 4);
                }
            }, plugin, true);
            pm.registerEvent(org.bukkit.event.block.BlockPlaceEvent.class, l, EventPriority.MONITOR, (li, e) -> {
                if (e instanceof org.bukkit.event.block.BlockPlaceEvent b) {
                    var blk = b.getBlock();
                    org.purpurmc.purpur.hybrid.lod.HorizonLodCache.global().invalidateChunk(blk.getWorld().getUID(), blk.getX() >> 4, blk.getZ() >> 4);
                }
            }, plugin, true);
            pm.registerEvent(InventoryClickEvent.class, l, EventPriority.HIGHEST, (li, e) -> {
                if (e instanceof InventoryClickEvent c && c.getInventory().getHolder() instanceof MenuHolder) {
                    c.setCancelled(true);
                    HybridAdminMenu.onClick(c);
                }
            }, plugin);
            pm.registerEvent(InventoryDragEvent.class, l, EventPriority.HIGHEST, (li, e) -> {
                if (e instanceof InventoryDragEvent d && d.getInventory().getHolder() instanceof MenuHolder) d.setCancelled(true);
            }, plugin);
            registered = true;
            registeredAtMs = System.currentTimeMillis();
            error = "";
        } catch (Throwable t) {
            error = t.toString();
            LOG.warning("[Hybrid] no se pudieron registrar los listeners internos: " + t);
        }
        return registered;
    }
}
