import io.github.thebusybiscuit.api.items.SlimefunItem;
import io.github.thebusybiscuit.implementation.Slimefun;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Prueba de humo FUNCIONAL de Slimefun/StarSuites sobre DrakesPurpur Hybrid (solo CI / servidor desechable).
 * -Dsmoke.phase=1: registra items, comprueba ida y vuelta ItemStack<->SlimefunItem, coloca bloques con BlockStorage y guarda.
 * -Dsmoke.phase=2: tras reiniciar, comprueba que los datos de BlockStorage persistieron.
 * Escribe compat-smoke-phaseN.txt y apaga el servidor con Bukkit.shutdown() (ruta normal, guarda el mundo).
 */
public class HybridCompatSmoke extends JavaPlugin {
    private final List<String> out = new ArrayList<>();
    private int failures;

    private void check(boolean ok, String what) {
        out.add((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }

    @Override
    public void onEnable() {
        int phase = Integer.getInteger("smoke.phase", 1);
        Bukkit.getScheduler().runTaskLater(this, () -> run(phase), 200L);
    }

    private void run(int phase) {
        try {
            out.add("== HybridCompatSmoke phase " + phase + " ==");
            Plugin sf = Bukkit.getPluginManager().getPlugin("Slimefun");
            check(sf != null && sf.isEnabled(), "Slimefun cargado y habilitado");
            for (Plugin p : Bukkit.getPluginManager().getPlugins()) out.add("plugin " + p.getName() + " " + (p.isEnabled() ? "ENABLED" : "DISABLED"));
            if (sf != null && sf.isEnabled()) {
                List<SlimefunItem> all = new ArrayList<>(Slimefun.getRegistry().getAllSlimefunItems());
                out.add("slimefun_items=" + all.size());
                check(all.size() > 100, "registro de Slimefun con >100 items");
                World w = Bukkit.getWorlds().get(0);
                int placed = 0, tested = 0;
                for (SlimefunItem it : all) {
                    if (tested >= 60) break;
                    ItemStack st = it.getItem();
                    if (st == null || it.isDisabled()) continue;
                    tested++;
                    check(SlimefunItem.getById(it.getId()) == it, "getById ida y vuelta " + it.getId());
                    check(SlimefunItem.getByItem(st.clone()) == it, "getByItem(ItemStack) ida y vuelta " + it.getId());
                    if (st.getType().isBlock() && placed < 20) {
                        Location l = new Location(w, 10 + placed, 120, 10, 0, 0);
                        if (phase == 1) {
                            w.getBlockAt(l).setType(st.getType());
                            BlockStorage.addBlockInfo(l, "id", it.getId());
                            BlockStorage.addBlockInfo(l, "hybridSmoke", "phase1-" + placed);
                        }
                        placed++;
                    }
                }
                if (phase == 1) {
                    for (int i = 0; i < placed; i++) {
                        Location l = new Location(w, 10 + i, 120, 10, 0, 0);
                        check(BlockStorage.hasBlockInfo(l), "BlockStorage tiene datos en bloque " + i);
                        check(("phase1-" + i).equals(BlockStorage.getLocationInfo(l, "hybridSmoke")), "BlockStorage lee lo escrito " + i);
                    }
                } else {
                    int ok = 0;
                    for (int i = 0; i < 20; i++) {
                        Location l = new Location(w, 10 + i, 120, 10, 0, 0);
                        if (("phase1-" + i).equals(BlockStorage.getLocationInfo(l, "hybridSmoke"))) ok++;
                    }
                    check(ok > 0, "PERSISTENCIA tras reinicio: " + ok + " bloques con datos de la fase 1");
                }
                out.add("tested_items=" + tested + " blocks=" + placed);
            }
        } catch (Throwable t) {
            check(false, "excepcion: " + t);
            t.printStackTrace();
        }
        out.add(failures == 0 ? "RESULT OK" : "RESULT FAILURES=" + failures);
        try {
            Files.write(new File("compat-smoke-phase" + Integer.getInteger("smoke.phase", 1) + ".txt").toPath(), out);
        } catch (Throwable ignored) {
        }
        Bukkit.shutdown();
    }
}
