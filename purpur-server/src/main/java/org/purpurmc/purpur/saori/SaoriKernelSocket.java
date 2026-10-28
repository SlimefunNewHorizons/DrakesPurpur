package org.purpurmc.purpur.saori;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Transporte motor -> SAORI. Cola en memoria acotada + un hilo propio; nunca bloquea al llamador.
 * Lleva todo por PrivacyGate. Destino: POST {endpoint}/kernel con Bearer (solo loopback).
 */
public final class SaoriKernelSocket {
    private static final Logger LOGGER = Logger.getLogger("SaoriLink");
    private static final int BATCH = 50;
    private static final long MAX_BACKOFF_MS = 60_000;

    private static volatile SaoriConfig cfg = null;
    private static volatile BlockingQueue<JsonObject> queue = new ArrayBlockingQueue<>(10);
    private static volatile Thread worker;
    private static volatile boolean enabled = false;

    private static final AtomicLong SENT = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong BLOCKED = new AtomicLong();
    private static volatile long lastOkMs;
    private static volatile long lastErrMs;
    private static volatile String lastError = "";

    private SaoriKernelSocket() {}

    public static synchronized void initialize(SaoriConfig config) {
        cfg = config;
        enabled = config.enabled;
        if (!enabled) {
            LOGGER.info("[SaoriLink] SAORI desactivada (" + (config.token == null ? config.tokenProblem : "enabled: false") + ").");
            return;
        }
        queue = new ArrayBlockingQueue<>(config.queueSize);
        Thread t = new Thread(SaoriKernelSocket::loop, "Saori-Kernel-Dispatcher");
        t.setDaemon(true);
        worker = t;
        t.start();
        LOGGER.info("[SaoriLink] Transporte activo hacia " + config.endpoint + " (cola " + config.queueSize + ").");
    }

    static void countBlocked() {
        BLOCKED.incrementAndGet();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static String stats() {
        return "cola=" + queue.size() + "/" + (cfg == null ? 0 : cfg.queueSize) + " enviados=" + SENT.get()
            + " descartados=" + DROPPED.get() + " bloqueados_privacidad=" + BLOCKED.get()
            + " ultimo_ok=" + age(lastOkMs) + " ultimo_error=" + (lastErrMs == 0 ? "-" : age(lastErrMs) + " (" + lastError + ")");
    }

    private static String age(long ms) {
        return ms == 0 ? "nunca" : ((System.currentTimeMillis() - ms) / 1000) + "s";
    }

    /** Encola un evento. kind + texto pasan por PrivacyGate. Nunca bloquea ni lanza. */
    public static boolean emit(String kind, String textForGate, JsonObject body) {
        SaoriConfig c = cfg;
        if (!enabled || c == null) return false;
        try {
            if (!c.gate.allow(kind, textForGate)) {
                BLOCKED.incrementAndGet();
                return false;
            }
            body.addProperty("type", kind);
            body.addProperty("timestamp", System.currentTimeMillis());
            BlockingQueue<JsonObject> q = queue;
            if (!q.offer(body)) {
                q.poll(); // descarta el mas viejo, conserva lo reciente
                DROPPED.incrementAndGet();
                q.offer(body);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void dispatchChat(String playerUuid, String playerName, String message, String worldName) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", playerUuid);
        o.addProperty("player", playerName);
        o.addProperty("message", message);
        o.addProperty("world", worldName);
        emit("chat", message, o);
    }

    public static void dispatchFault(String component, String details, String trace) {
        JsonObject o = new JsonObject();
        o.addProperty("component", component);
        o.addProperty("details", details);
        o.addProperty("trace", trace);
        emit("fault", component + " " + details + " " + trace, o);
    }

    public static void dispatchTelemetry(double tps, double mspt, int onlinePlayers, long freeMemoryMb) {
        JsonObject o = new JsonObject();
        o.addProperty("tps", Math.round(tps * 100) / 100.0);
        o.addProperty("mspt", Math.round(mspt * 100) / 100.0);
        o.addProperty("players", onlinePlayers);
        o.addProperty("free_ram_mb", freeMemoryMb);
        emit("telemetry", null, o);
    }

    private static void loop() {
        long backoff = 0;
        List<JsonObject> batch = new ArrayList<>(BATCH);
        while (true) {
            try {
                if (backoff > 0) Thread.sleep(backoff);
                batch.clear();
                JsonObject first = queue.take();
                batch.add(first);
                queue.drainTo(batch, BATCH - 1);
                JsonArray arr = new JsonArray();
                batch.forEach(arr::add);
                JsonObject payload = new JsonObject();
                payload.add("events", arr);
                post("/kernel", payload.toString(), cfg.timeoutMs, cfg.timeoutMs);
                SENT.addAndGet(batch.size());
                lastOkMs = System.currentTimeMillis();
                backoff = 0;
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                lastErrMs = System.currentTimeMillis();
                lastError = t.getClass().getSimpleName();
                // el lote fallido se pierde (log/telemetria no son criticos); backoff exponencial
                DROPPED.addAndGet(batch.size());
                backoff = backoff == 0 ? 1000 : Math.min(MAX_BACKOFF_MS, backoff * 2);
            }
        }
    }

    /** POST JSON con Bearer; devuelve el cuerpo de la respuesta. Lanza si status >= 400 o hay timeout. */
    static String post(String path, String json, int connectMs, int readMs) throws java.io.IOException {
        SaoriConfig c = cfg;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(c.endpoint + path).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "DrakesPurpur-Kernel/26.2");
            conn.setRequestProperty("Authorization", "Bearer " + c.token);
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code >= 400) throw new java.io.IOException("HTTP " + code);
            try (var in = conn.getInputStream()) {
                return new String(in.readNBytes(65536), StandardCharsets.UTF_8);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
