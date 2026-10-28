package org.purpurmc.purpur.saori;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

/**
 * SaoriLink — integracion directa del motor con SAORI (logs, chat, comandos, acciones). Sin base de datos.
 * Desactivada por defecto; ver drakes-data/saori.yml.
 */
public final class SaoriLink {
    private static final Logger LOGGER = Logger.getLogger("SaoriLink");
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Map<String, Long> ASK_COOLDOWN = new ConcurrentHashMap<>();
    private static final ExecutorService ASK_POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Saori-Ask");
        t.setDaemon(true);
        return t;
    });

    private static volatile SaoriConfig cfg = new SaoriConfig();
    private static SaoriLogAppender appender;

    private SaoriLink() {}

    public static SaoriConfig config() {
        return cfg;
    }

    public static synchronized void initialize() {
        SaoriConfig c = SaoriConfig.load();
        cfg = c;
        SaoriKernelSocket.initialize(c);
        if (!c.enabled) return;
        try {
            if (c.logsEnabled) attachAppender(c);
            registerListeners(c);
            SaoriAutomations.init(c);
            if (c.inboundEnabled) SaoriInbound.start(c);
            Runtime.getRuntime().addShutdownHook(new Thread(SaoriLink::shutdown, "Saori-Shutdown"));
            LOGGER.info("[SaoriLink] Activa: logs=" + c.logsEnabled + " chat=" + c.chatForward + " entrada=" + SaoriInbound.running());
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[SaoriLink] Fallo al iniciar (el servidor sigue): " + t);
        }
    }

    private static void shutdown() {
        try {
            SaoriInbound.stop();
            if (appender != null) {
                LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
                ctx.getConfiguration().getRootLogger().removeAppender(appender.getName());
                appender.stop();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void attachAppender(SaoriConfig c) {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration conf = ctx.getConfiguration();
        appender = new SaoriLogAppender(c);
        appender.start();
        conf.addAppender(appender);
        conf.getRootLogger().addAppender(appender, null, null);
        ctx.updateLoggers();
    }

    // ---- eventos del juego ---------------------------------------------------------------

    /** Plugin "fantasma" del kernel para poder registrar listeners sin plugin real. */
    private static Plugin kernelPlugin() {
        return (Plugin) Proxy.newProxyInstance(SaoriLink.class.getClassLoader(), new Class<?>[] {Plugin.class}, (p, m, a) -> switch (m.getName()) {
            case "isEnabled" -> true;
            case "getName" -> "DrakesKernel";
            case "getLogger" -> Logger.getLogger("DrakesKernel");
            case "isNaggable" -> false;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            case "toString" -> "DrakesKernel";
            default -> null;
        });
    }

    private static void registerListeners(SaoriConfig c) {
        Plugin kp = kernelPlugin();
        Listener l = new Listener() {};
        var pm = Bukkit.getPluginManager();
        if (c.chatForward) {
            pm.registerEvent(AsyncChatEvent.class, l, EventPriority.MONITOR, (li, e) -> onChat((AsyncChatEvent) e), kp, true);
        }
        List<String> ev = c.chatEvents;
        if (ev.contains("join")) pm.registerEvent(PlayerJoinEvent.class, l, EventPriority.MONITOR,
            (li, e) -> publicEvent("join", ((PlayerJoinEvent) e).getPlayer().getName()), kp, true);
        if (ev.contains("quit")) pm.registerEvent(PlayerQuitEvent.class, l, EventPriority.MONITOR,
            (li, e) -> publicEvent("quit", ((PlayerQuitEvent) e).getPlayer().getName()), kp, true);
        if (ev.contains("death")) pm.registerEvent(PlayerDeathEvent.class, l, EventPriority.MONITOR, (li, e) -> {
            PlayerDeathEvent d = (PlayerDeathEvent) e;
            Component m = d.deathMessage();
            publicEvent("death", m == null ? d.getPlayer().getName() : PLAIN.serialize(m));
        }, kp, true);
        if (ev.contains("advancement")) pm.registerEvent(PlayerAdvancementDoneEvent.class, l, EventPriority.MONITOR, (li, e) -> {
            PlayerAdvancementDoneEvent d = (PlayerAdvancementDoneEvent) e;
            Component m = d.message();
            if (m != null) publicEvent("advancement", PLAIN.serialize(m));
        }, kp, true);
        pm.registerEvent(PluginEnableEvent.class, l, EventPriority.MONITOR,
            (li, e) -> SaoriAutomations.onPluginEnabled(((PluginEnableEvent) e).getPlugin().getName()), kp, false);
    }

    private static void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        // solo chat que llega a TODOS los jugadores conectados; si alguien filtro destinatarios es privado
        var viewers = e.viewers();
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!viewers.contains(o)) return;
        }
        String msg = PLAIN.serialize(e.message());
        SaoriAutomations.onChat(p.getName());
        SaoriKernelSocket.dispatchChat(p.getUniqueId().toString(), p.getName(), msg, p.getWorld().getName());
    }

    private static void publicEvent(String kind, String text) {
        SaoriAutomations.recordPublic(kind, text);
        JsonObject o = new JsonObject();
        o.addProperty("text", text);
        SaoriKernelSocket.emit(kind, text, o);
    }

    // ---- SAORI -> juego ------------------------------------------------------------------

    static void runOnMain(Runnable r) {
        MinecraftServer.getServer().execute(r);
    }

    private static String clean(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch >= 32 && ch != '§' && ch != 127) b.append(ch);
        }
        return b.length() > 256 ? b.substring(0, 256) : b.toString();
    }

    /** Hilo principal. */
    static void broadcast(String text) {
        Bukkit.broadcast(Component.text(cfg.prefix + " " + clean(text)));
    }

    static void broadcastAsync(String text) {
        runOnMain(() -> broadcast(text));
    }

    /** Hilo principal. */
    static boolean tell(String player, String text) {
        Player p = Bukkit.getPlayerExact(player);
        if (p == null) return false;
        p.sendMessage(Component.text(cfg.prefix + " " + clean(text)));
        return true;
    }

    // ---- comandos ------------------------------------------------------------------------

    /** /saori <pregunta> | /saori status */
    public static void command(CommandSender sender, String[] args) {
        if (!cfg.enabled) {
            sender.sendMessage("[SAORI] desactivada en este servidor.");
            return;
        }
        if (args.length == 0) {
            sender.sendMessage("[SAORI] Uso: /saori <pregunta> | /saori status");
            return;
        }
        if (args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("[SAORI] " + SaoriAutomations.healthText());
            return;
        }
        String q = clean(String.join(" ", args));
        if (cfg.gate.isPrivateCommand(q)) {
            sender.sendMessage("[SAORI] Eso parece un mensaje privado; no lo envio.");
            return;
        }
        long now = System.currentTimeMillis();
        Long last = ASK_COOLDOWN.put(sender.getName(), now);
        if (last != null && now - last < 5000) {
            sender.sendMessage("[SAORI] Espera unos segundos entre preguntas.");
            return;
        }
        if (ASK_COOLDOWN.size() > 500) ASK_COOLDOWN.clear();
        ASK_POOL.execute(() -> {
            String reply;
            try {
                JsonObject o = new JsonObject();
                o.addProperty("player", sender.getName());
                o.addProperty("question", q);
                String body = SaoriKernelSocket.post("/ask", o.toString(), cfg.timeoutMs, cfg.askTimeoutSeconds * 1000);
                JsonObject r = body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
                reply = r.has("reply") ? r.get("reply").getAsString() : "Recibido; SAORI respondera en el chat si corresponde.";
            } catch (Throwable t) {
                reply = "SAORI no responde ahora mismo.";
            }
            String out = reply;
            runOnMain(() -> sender.sendMessage(Component.text(cfg.prefix + " " + clean(out))));
        });
    }

    /** /drakes saori status|check|test */
    public static void admin(CommandSender sender, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase() : "status";
        switch (sub) {
            case "status" -> {
                sender.sendMessage("[SAORI] enabled=" + cfg.enabled + " token=" + cfg.tokenProblem + " endpoint=" + cfg.endpoint);
                sender.sendMessage("[SAORI] " + SaoriKernelSocket.stats());
                sender.sendMessage("[SAORI] entrada=" + (SaoriInbound.running() ? "127.0.0.1:" + cfg.inboundPort : "apagada")
                    + " run-command-allowlist=" + cfg.runCommandAllowlist.size() + " logs=" + (appender != null));
            }
            case "test" -> {
                if (!cfg.enabled) {
                    sender.sendMessage("[SAORI] desactivada: " + cfg.tokenProblem);
                    return;
                }
                ASK_POOL.execute(() -> {
                    String res;
                    try {
                        JsonObject ev = new JsonObject();
                        ev.addProperty("type", "ping");
                        ev.addProperty("timestamp", System.currentTimeMillis());
                        JsonObject payload = new JsonObject();
                        var arr = new com.google.gson.JsonArray();
                        arr.add(ev);
                        payload.add("events", arr);
                        long t0 = System.nanoTime();
                        SaoriKernelSocket.post("/kernel", payload.toString(), cfg.timeoutMs, cfg.timeoutMs);
                        res = "ping OK (" + (System.nanoTime() - t0) / 1_000_000 + " ms)";
                    } catch (Throwable t) {
                        res = "ping FALLO: " + t.getClass().getSimpleName() + " " + t.getMessage();
                    }
                    String out = res;
                    runOnMain(() -> sender.sendMessage("[SAORI] " + out));
                });
            }
            case "check" -> check(sender);
            default -> sender.sendMessage("[SAORI] Uso: /drakes saori status|check|test");
        }
    }

    private static final List<String> PRIVATE_SAMPLES = List.of("/msg a b", "/tell a b", "/w a b", "/r b", "/reply b", "/m a b",
        "/t a b", "/pm a b", "/mail send a b", "/tc x", "/teamchat x", "/team chat x", "/party x", "/clan chat x",
        "/execute run msg a b");

    private static void check(CommandSender sender) {
        int leaks = 0;
        for (String s : PRIVATE_SAMPLES) {
            if (cfg.gate.allow("telemetry", s) || cfg.gate.allow("chat", s)) leaks++;
        }
        if (cfg.gate.allow("log", "Jack issued server command: /msg a b")) leaks++;
        sender.sendMessage("[SAORI] PrivacyGate autoprueba: " + (leaks == 0 ? "VERDE" : "ROJO (" + leaks + " fugas)"));
        sender.sendMessage("[SAORI] token: " + cfg.tokenProblem + " | destino loopback: " + cfg.endpoint);
        sender.sendMessage("[SAORI] run-command: " + (cfg.runCommandAllowlist.isEmpty() ? "deshabilitado (allowlist vacia)" : cfg.runCommandAllowlist.size() + " prefijos"));
        try {
            File discord = new File("plugins/DiscordSRV/config.yml");
            if (discord.isFile()) {
                for (String line : Files.readAllLines(discord.toPath())) {
                    if (line.startsWith("DiscordConsoleChannelId:") && !line.replaceAll("[^0-9]", "").replace("0", "").isEmpty()) {
                        sender.sendMessage("[SAORI] ROJO: DiscordSRV tiene canal de consola (la consola completa saldria a Discord).");
                    }
                }
            }
            File bt = new File("plugins/BetterTeams/config.yml");
            if (bt.isFile()) {
                for (String line : Files.readAllLines(bt.toPath())) {
                    if (line.strip().startsWith("logTeamChat:") && line.contains("true")) {
                        sender.sendMessage("[SAORI] AMARILLO: BetterTeams logTeamChat=true (el motor lo filtra, pero conviene false).");
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
