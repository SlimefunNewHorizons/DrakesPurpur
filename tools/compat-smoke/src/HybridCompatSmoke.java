import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Properties;
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

    private static boolean hierarchyHas(Class<?> c, String frag) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (k.getName().contains(frag)) return true;
            for (Class<?> i : k.getInterfaces()) if (i.getName().contains(frag)) return true;
        }
        return false;
    }

    private static Object call(Object o, String m) {
        try {
            return o.getClass().getMethod(m).invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String js(String s) {
        return "\"" + String.valueOf(s).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Auditor de catalogo: cuenta desde el registro REAL de Slimefun (tras cargar todos los addons) y compara con el volcado esperado. */
    private void writeCatalog(List<SlimefunItem> all) throws Exception {
        Map<String, Integer> byAddon = new TreeMap<>();
        Map<String, Integer> byGroup = new TreeMap<>();
        int recipes = 0, disabled = 0, energy = 0, multiblocks = 0, machines = 0;
        for (SlimefunItem it : all) {
            Object addon = call(it, "getAddon");
            String an = addon == null ? "(sin addon)" : String.valueOf(call(addon, "getName"));
            byAddon.merge(an, 1, Integer::sum);
            Object grp = call(it, "getItemGroup");
            Object key = grp == null ? null : call(grp, "getKey");
            byGroup.merge(key == null ? "(sin grupo)" : key.toString(), 1, Integer::sum);
            ItemStack[] rec = it.getRecipe();
            if (rec != null) for (ItemStack r : rec) if (r != null) { recipes++; break; }
            if (it.isDisabled()) disabled++;
            if (hierarchyHas(it.getClass(), "EnergyNetComponent")) energy++;
            if (hierarchyHas(it.getClass(), "MultiBlock")) multiblocks++;
            if (hierarchyHas(it.getClass(), "AContainer") || hierarchyHas(it.getClass(), "AbstractMachine") || hierarchyHas(it.getClass(), "MachineBlock")) machines++;
        }
        Object reg = Slimefun.getRegistry();
        Object groups = call(reg, "getAllItemGroups");
        Object researches = call(reg, "getResearches");
        int nGroups = groups instanceof java.util.Collection<?> c ? c.size() : -1;
        int nResearch = researches instanceof java.util.Collection<?> c ? c.size() : -1;
        Properties expected = new Properties();
        String ep = System.getProperty("catalog.expected");
        if (ep != null && new File(ep).isFile()) try (var in = Files.newBufferedReader(new File(ep).toPath())) { expected.load(in); }
        int expItems = Integer.parseInt(expected.getProperty("__items", "-1"));
        int expGroups = Integer.parseInt(expected.getProperty("__groups", "-1"));
        int expResearch = Integer.parseInt(expected.getProperty("__researches", "-1"));
        List<String> missing = new ArrayList<>(), extra = new ArrayList<>(), partial = new ArrayList<>();
        for (String k : expected.stringPropertyNames()) {
            if (k.startsWith("__")) continue;
            int e = Integer.parseInt(expected.getProperty(k));
            Integer f = byAddon.get(k);
            if (f == null) missing.add(k + " (esperados " + e + ")");
            else if (f < e) partial.add(k + " " + f + "/" + e);
        }
        for (String k : byAddon.keySet()) if (!expected.containsKey(k) && !expected.isEmpty()) extra.add(k + " (" + byAddon.get(k) + ")");
        Plugin[] ps = Bukkit.getPluginManager().getPlugins();
        int en = 0;
        StringBuilder pl = new StringBuilder();
        for (Plugin p : ps) { if (p.isEnabled()) en++; pl.append(pl.length() == 0 ? "" : ",").append(js(p.getName() + (p.isEnabled() ? "" : " [DESHABILITADO]"))); }
        StringBuilder j = new StringBuilder();
        j.append("{\n  \"schema\": 1,\n  \"provenance\": {\"server\": ").append(js(Bukkit.getVersion())).append(", \"slimefun\": ")
            .append(js(Bukkit.getPluginManager().getPlugin("Slimefun").getDescription().getVersion())).append(", \"generated_at\": ")
            .append(js(java.time.Instant.now().toString())).append(", \"method\": \"Slimefun.getRegistry() despues de cargar todos los plugins; reflexion para grupos/investigaciones\", \"expected_source\": ")
            .append(js(ep == null ? "(ninguno)" : ep)).append("},\n");
        j.append("  \"totals\": {\"items\": ").append(all.size()).append(", \"items_with_recipe\": ").append(recipes).append(", \"items_disabled\": ").append(disabled)
            .append(", \"item_groups\": ").append(nGroups).append(", \"researches\": ").append(nResearch).append(", \"energy_components\": ").append(energy)
            .append(", \"multiblocks\": ").append(multiblocks).append(", \"machines_heuristic\": ").append(machines)
            .append(", \"addons_with_items\": ").append(byAddon.size()).append(", \"plugins_total\": ").append(ps.length).append(", \"plugins_enabled\": ").append(en).append("},\n");
        j.append("  \"expected_totals\": {\"items\": ").append(expItems).append(", \"item_groups\": ").append(expGroups).append(", \"researches\": ").append(expResearch).append("},\n");
        j.append("  \"items_by_addon\": {");
        boolean first = true;
        for (var e : byAddon.entrySet()) { j.append(first ? "" : ", ").append(js(e.getKey())).append(": ").append(e.getValue()); first = false; }
        j.append("},\n  \"items_by_group\": {");
        first = true;
        for (var e : byGroup.entrySet()) { j.append(first ? "" : ", ").append(js(e.getKey())).append(": ").append(e.getValue()); first = false; }
        j.append("},\n  \"expected_addons_missing\": [");
        for (int i = 0; i < missing.size(); i++) j.append(i == 0 ? "" : ", ").append(js(missing.get(i)));
        j.append("],\n  \"expected_addons_partial\": [");
        for (int i = 0; i < partial.size(); i++) j.append(i == 0 ? "" : ", ").append(js(partial.get(i)));
        j.append("],\n  \"found_addons_not_in_expected\": [");
        for (int i = 0; i < extra.size(); i++) j.append(i == 0 ? "" : ", ").append(js(extra.get(i)));
        j.append("],\n  \"plugins\": [").append(pl).append("]\n}\n");
        Files.writeString(new File("compat-catalog.json").toPath(), j.toString());
        StringBuilder m = new StringBuilder("# Catalogo real de Slimefun en esta instalacion de prueba\n\n");
        m.append("Fuente: registro de Slimefun tras cargar todos los plugins (").append(Bukkit.getVersion()).append(").\n\n");
        m.append("| Medida | Encontrado | Esperado (volcado de produccion) |\n|---|---|---|\n");
        m.append("| Items registrados | ").append(all.size()).append(" | ").append(expItems < 0 ? "n/d" : expItems).append(" |\n");
        m.append("| Grupos (categorias) | ").append(nGroups).append(" | ").append(expGroups < 0 ? "n/d" : expGroups).append(" |\n");
        m.append("| Investigaciones | ").append(nResearch).append(" | ").append(expResearch < 0 ? "n/d" : expResearch).append(" |\n");
        m.append("| Items con receta | ").append(recipes).append(" | - |\n| Componentes de energia | ").append(energy).append(" | - |\n");
        m.append("| Multibloques | ").append(multiblocks).append(" | - |\n| Maquinas (heuristica) | ").append(machines).append(" | - |\n");
        m.append("| Addons con items | ").append(byAddon.size()).append(" | ").append(expected.isEmpty() ? "n/d" : expected.stringPropertyNames().stream().filter(k -> !k.startsWith("__")).count()).append(" |\n");
        m.append("| Plugins habilitados | ").append(en).append(" de ").append(ps.length).append(" | - |\n\n");
        m.append("## Items por addon (encontrados)\n\n");
        for (var e : byAddon.entrySet()) m.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        m.append("\n## Addons esperados ausentes (").append(missing.size()).append(")\n\n");
        for (String x : missing) m.append("- ").append(x).append("\n");
        m.append("\n## Addons parciales (").append(partial.size()).append(")\n\n");
        for (String x : partial) m.append("- ").append(x).append("\n");
        Files.writeString(new File("compat-catalog.md").toPath(), m.toString());
        out.add("catalog: items=" + all.size() + " grupos=" + nGroups + " investigaciones=" + nResearch + " addons=" + byAddon.size() + " recetas=" + recipes
            + " energia=" + energy + " multibloques=" + multiblocks + " maquinas~" + machines + " | esperado items=" + expItems + " ausentes=" + missing.size() + " parciales=" + partial.size());
    }

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
                if (phase == 1) {
                    try {
                        writeCatalog(all);
                    } catch (Throwable t) {
                        check(false, "auditor de catalogo: " + t);
                        t.printStackTrace();
                    }
                }
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
