package org.purpurmc.purpur.hybrid;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.purpurmc.purpur.nativelib.HybridNative;

/**
 * GUI administrativa del Hybrid Engine. Todo dato mostrado se lee en el momento de abrir (nada simulado) y los unicos
 * controles son los que existen de verdad: conmutar en caliente la ruta nativa de bits y el prefetch de chunks
 * (cambios SOLO de la sesion, no se guardan: para persistir se edita drakes-data/perf.yml y se reinicia). Cada conmutacion
 * exige una segunda pulsacion en menos de 5 s.
 */
public final class HybridAdminMenu {
    public static final String TITLE = ChatColor.DARK_GREEN + "Hybrid Engine " + ChatColor.GRAY + "(admin)";
    private static final int SLOT_STATUS = 10, SLOT_ACCEL = 11, SLOT_CHUNKS = 12, SLOT_PERF = 13, SLOT_ERRORS = 14,
        SLOT_BENCH = 15, SLOT_COMPAT = 16, SLOT_TOGGLE_BITS = 20, SLOT_TOGGLE_PREFETCH = 22, SLOT_CLOSE = 26;
    private static final Map<UUID, long[]> CONFIRM = new ConcurrentHashMap<>(); // jugador -> {slot, t}

    private HybridAdminMenu() {}

    /** Construye el inventario con datos reales (sin necesitar jugador: se usa tambien en pruebas). */
    public static Inventory build() {
        HybridEvents.MenuHolder holder = new HybridEvents.MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, TITLE);
        holder.bind(inv);
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);
        var s = HybridNative.snapshot();
        inv.setItem(SLOT_STATUS, item(s.libraryLoaded() ? Material.EMERALD_BLOCK : Material.RED_CONCRETE, ChatColor.GREEN + "Estado del motor",
            "Biblioteca Rust: " + (s.libraryLoaded() ? "CARGADA" : "no cargada (modo Java)"),
            "ABI biblioteca / esperada: " + s.libraryAbi() + " / " + s.expectedAbi(),
            "Ruta: " + (s.libraryPath() == null ? "-" : s.libraryPath()),
            "Modo: " + (s.libraryLoaded() && (s.bits() || s.noise() || s.region()) ? "HIBRIDO (parcial)" : "JAVA original")));
        inv.setItem(SLOT_ACCEL, item(Material.REDSTONE_TORCH, ChatColor.AQUA + "Aceleraciones",
            "bits (SimpleBitStorage): " + onOff(s.bits()), "noise (Perlin por lotes): " + onOff(s.noise()),
            "region (cabeceras .mca): " + onOff(s.region()), "min-batch: " + s.minBatch(),
            "prefetch de chunks: " + onOff(HybridChunkPrefetch.enabled()),
            "Fallback Java: siempre disponible"));
        inv.setItem(SLOT_CHUNKS, item(Material.GRASS_BLOCK, ChatColor.YELLOW + "Chunks",
            "Cargas nuevas/existentes (desde registro): " + HybridEvents.CHUNK_LOADS_NEW.sum() + " / " + HybridEvents.CHUNK_LOADS_EXISTING.sum(),
            "Envios a jugadores: " + HybridEvents.CHUNK_SENDS.sum(),
            "Contadores activos: " + (HybridEvents.registered() ? "si" : "no (" + HybridEvents.error() + ")"),
            "Prefetch en vuelo: " + HybridChunkPrefetch.inFlight()));
        double[] tps = Bukkit.getTPS();
        Runtime rt = Runtime.getRuntime();
        inv.setItem(SLOT_PERF, item(Material.CLOCK, ChatColor.GOLD + "Rendimiento",
            String.format("TPS 1m: %.2f", tps[0]), String.format("MSPT medio: %.2f ms", Bukkit.getAverageTickTime()),
            "RAM usada: " + ((rt.totalMemory() - rt.freeMemory()) >> 20) + " / " + (rt.maxMemory() >> 20) + " MB",
            "Hilos: " + Thread.activeCount()));
        inv.setItem(SLOT_ERRORS, item(s.failures() == 0 ? Material.LIME_DYE : Material.ORANGE_DYE, ChatColor.RED + "Errores nativos",
            "Fallos acumulados: " + s.failures(), "Ultimo: " + (s.lastError().isEmpty() ? "-" : s.lastError()),
            "Un fallo apaga esa ruta nativa hasta reiniciar."));
        inv.setItem(SLOT_BENCH, item(Material.COMPARATOR, ChatColor.LIGHT_PURPLE + "Benchmark",
            "Ultimo resultado:", HybridAdmin.lastBenchmark(), "Ejecutar: /drakes hybrid benchmark confirm"));
        inv.setItem(SLOT_COMPAT, item(Material.CHEST, ChatColor.DARK_AQUA + "Compatibilidad",
            HybridAdmin.compatLines().toArray(new String[0])));
        inv.setItem(SLOT_TOGGLE_BITS, item(Material.LEVER, ChatColor.WHITE + "Conmutar ruta nativa de bits",
            "Estado: " + onOff(s.bits()), s.libraryLoaded() ? "Doble clic para cambiar (solo esta sesion)" : "No disponible: biblioteca no cargada"));
        inv.setItem(SLOT_TOGGLE_PREFETCH, item(Material.ELYTRA, ChatColor.WHITE + "Conmutar prefetch de chunks",
            "Estado: " + onOff(HybridChunkPrefetch.enabled()), "Doble clic para cambiar (solo esta sesion)",
            "Persistente: native.hybrid.prefetch.enabled en perf.yml + reinicio"));
        inv.setItem(SLOT_CLOSE, item(Material.BARRIER, ChatColor.RED + "Cerrar"));
        return inv;
    }

    public static boolean open(Player p) {
        if (!HybridEvents.ensureRegistered()) {
            p.sendMessage(ChatColor.RED + "La GUI necesita los listeners internos y no se pudieron registrar: " + HybridEvents.error());
            return false;
        }
        p.openInventory(build());
        return true;
    }

    static void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!p.hasPermission("drakes.hybrid.admin")) return;
        int slot = e.getRawSlot();
        if (slot == SLOT_CLOSE) {
            p.closeInventory();
            return;
        }
        if (slot != SLOT_TOGGLE_BITS && slot != SLOT_TOGGLE_PREFETCH) return;
        long now = System.currentTimeMillis();
        long[] prev = CONFIRM.get(p.getUniqueId());
        if (prev == null || prev[0] != slot || now - prev[1] > 5000) {
            CONFIRM.put(p.getUniqueId(), new long[] {slot, now});
            p.sendMessage(ChatColor.YELLOW + "Pulsa de nuevo en 5 s para confirmar el cambio (solo esta sesion).");
            return;
        }
        CONFIRM.remove(p.getUniqueId());
        if (slot == SLOT_TOGGLE_BITS) {
            boolean now1 = !HybridNative.snapshot().bits();
            if (HybridNative.forceBits(now1)) p.sendMessage(ChatColor.GREEN + "Ruta nativa de bits: " + onOff(now1));
            else p.sendMessage(ChatColor.RED + "No disponible: la biblioteca nativa no esta cargada.");
        } else {
            HybridChunkPrefetch.setEnabled(!HybridChunkPrefetch.enabled());
            p.sendMessage(ChatColor.GREEN + "Prefetch de chunks: " + onOff(HybridChunkPrefetch.enabled()));
        }
        p.openInventory(build());
    }

    private static String onOff(boolean b) {
        return b ? "ACTIVADO" : "desactivado";
    }

    private static ItemStack item(Material m, String name, String... lore) {
        ItemStack st = new ItemStack(m);
        ItemMeta meta = st.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            List<String> l = new ArrayList<>();
            for (String line : lore) l.add(ChatColor.GRAY + line);
            meta.setLore(l);
            st.setItemMeta(meta);
        }
        return st;
    }
}
