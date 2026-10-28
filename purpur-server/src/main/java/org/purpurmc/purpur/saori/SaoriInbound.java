package org.purpurmc.purpur.saori;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** SAORI -> motor: HTTP solo en 127.0.0.1, Bearer, acciones de lista cerrada, todo auditado. */
final class SaoriInbound {
    private static final Logger LOGGER = Logger.getLogger("SaoriLink");
    private static final Path AUDIT = Path.of("drakes-data/saori-audit.log");
    private static volatile HttpServer server;
    private static final AtomicLong WINDOW = new AtomicLong();
    private static final AtomicLong COUNT = new AtomicLong();

    private SaoriInbound() {}

    static void start(SaoriConfig cfg) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), cfg.inboundPort), 8);
        s.createContext("/saori/action", ex -> handle(cfg, ex));
        s.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Saori-Inbound");
            t.setDaemon(true);
            return t;
        }));
        s.start();
        server = s;
        LOGGER.info("[SaoriLink] Entrada SAORI->motor en 127.0.0.1:" + cfg.inboundPort);
    }

    static void stop() {
        HttpServer s = server;
        if (s != null) s.stop(0);
    }

    static boolean running() {
        return server != null;
    }

    private static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private static void handle(SaoriConfig cfg, HttpExchange ex) throws IOException {
        String action = "?";
        try {
            if (!ex.getRemoteAddress().getAddress().isLoopbackAddress() || !"POST".equals(ex.getRequestMethod())) {
                reply(ex, 403, "{\"error\":\"forbidden\"}");
                return;
            }
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String expected = "Bearer " + cfg.token;
            if (auth == null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
                audit("auth-fail", "");
                reply(ex, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            long now = System.currentTimeMillis() / 1000;
            if (WINDOW.getAndSet(now) != now) COUNT.set(0);
            if (COUNT.incrementAndGet() > 20) {
                reply(ex, 429, "{\"error\":\"rate\"}");
                return;
            }
            byte[] raw = ex.getRequestBody().readNBytes(8193);
            if (raw.length > 8192) {
                reply(ex, 413, "{\"error\":\"too large\"}");
                return;
            }
            JsonObject req = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
            action = req.has("action") ? req.get("action").getAsString() : "";
            String result = run(cfg, action, req);
            audit(action, "ok " + summary(req));
            reply(ex, 200, result);
        } catch (DeniedException d) {
            audit(action, "DENEGADO " + d.getMessage());
            reply(ex, 403, "{\"error\":\"" + d.getMessage() + "\"}");
        } catch (Throwable t) {
            audit(action, "error " + t.getClass().getSimpleName());
            reply(ex, 400, "{\"error\":\"bad request\"}");
        }
    }

    private static final class DeniedException extends RuntimeException {
        DeniedException(String m) {
            super(m);
        }
    }

    private static String summary(JsonObject req) {
        String s = req.toString();
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    private static String run(SaoriConfig cfg, String action, JsonObject req) throws Exception {
        JsonObject out = new JsonObject();
        switch (action) {
            case "say" -> {
                if (!cfg.saoriCanSpeak) throw new DeniedException("speak-disabled");
                String text = req.get("text").getAsString();
                onMain(() -> SaoriLink.broadcast(text));
            }
            case "tell" -> {
                if (!cfg.saoriCanSpeak) throw new DeniedException("speak-disabled");
                String player = req.get("player").getAsString();
                String text = req.get("text").getAsString();
                boolean[] found = new boolean[1];
                onMain(() -> found[0] = SaoriLink.tell(player, text));
                out.addProperty("delivered", found[0]);
            }
            case "status" -> {
                return SaoriAutomations.healthJson().toString();
            }
            case "list" -> {
                JsonArray a = new JsonArray();
                for (Player p : Bukkit.getOnlinePlayers()) a.add(p.getName());
                out.add("players", a);
            }
            case "run-command" -> {
                String cmd = req.get("command").getAsString().strip();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                if (!commandAllowed(cfg, cmd)) throw new DeniedException("command-not-allowed");
                String c = cmd;
                onMain(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c));
            }
            case "prepare-restart" -> out.addProperty("result", SaoriAutomations.prepareRestart(req.get("seconds").getAsInt()));
            case "cancel-restart" -> out.addProperty("result", SaoriAutomations.cancelRestart());
            default -> throw new DeniedException("unknown-action");
        }
        out.addProperty("ok", true);
        return out.toString();
    }

    /** Allowlist por prefijo de palabras completas; vacia = nada permitido. Sin saltos de linea ni encadenado. */
    static boolean commandAllowed(SaoriConfig cfg, String cmd) {
        if (cmd.isEmpty() || cmd.length() > 256 || cmd.indexOf('\n') >= 0 || cmd.indexOf('\r') >= 0 || cmd.indexOf('\0') >= 0) return false;
        if (cfg.gate.isPrivateCommand(cmd)) return false;
        String low = cmd.toLowerCase(java.util.Locale.ROOT);
        for (String a : cfg.runCommandAllowlist) {
            String p = a.strip().toLowerCase(java.util.Locale.ROOT);
            if (p.startsWith("/")) p = p.substring(1);
            if (!p.isEmpty() && (low.equals(p) || low.startsWith(p + " "))) return true;
        }
        return false;
    }

    private static void onMain(Runnable r) throws Exception {
        CompletableFuture<Void> f = new CompletableFuture<>();
        SaoriLink.runOnMain(() -> {
            try {
                r.run();
                f.complete(null);
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        f.get(3, TimeUnit.SECONDS);
    }

    static synchronized void audit(String action, String detail) {
        try {
            String line = java.time.Instant.now() + " " + action + " " + detail.replace('\n', ' ') + "\n";
            Files.writeString(AUDIT, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
