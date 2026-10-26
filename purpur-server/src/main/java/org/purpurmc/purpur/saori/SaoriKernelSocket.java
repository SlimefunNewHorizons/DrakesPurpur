package org.purpurmc.purpur.saori;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * SaoriKernelSocket — Direct Zero-Lag Bridge between Purpur Kernel and SAORI SRE Core.
 * Dispatches async health pings, fault dumps, and chat events directly to Saori's listener
 * at http://127.0.0.1:8089/kernel or remote Star VPS endpoint without Bukkit event overhead.
 * Author: Jack (JackStar6677-1) - Star Systems & DrakesCraft Labs
 */
public final class SaoriKernelSocket {
    private static final Logger LOGGER = Logger.getLogger("SaoriKernelSocket");
    private static final ExecutorService ASYNC_DISPATCHER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Saori-Kernel-Dispatcher");
        t.setDaemon(true);
        return t;
    });

    private static String ingestUrl = "http://127.0.0.1:8089/kernel";
    private static String bearerToken = "";
    private static volatile boolean enabled = false;

    private SaoriKernelSocket() {}

    public static void initialize() {
        File cfg = new File("saori_bridge.properties");
        if (cfg.exists()) {
            try (FileInputStream in = new FileInputStream(cfg)) {
                Properties props = new Properties();
                props.load(in);
                ingestUrl = props.getProperty("ingest_url", "http://127.0.0.1:8089/kernel");
                bearerToken = props.getProperty("bearer_token", "");
                enabled = Boolean.parseBoolean(props.getProperty("enabled", "true"));
            } catch (Throwable t) {
                LOGGER.warning("[SaoriBridge] No se pudo leer saori_bridge.properties, usando defaults.");
            }
        }
        LOGGER.info("[SaoriBridge] Direct Kernel Bridge active. Target endpoint: " + ingestUrl);
    }

    public static ExecutorService getAsyncExecutor() {
        return ASYNC_DISPATCHER;
    }

    public static void dispatchChat(String playerUuid, String playerName, String message, String worldName) {
        dispatchJson(String.format(
            "{\"type\":\"chat\",\"uuid\":\"%s\",\"player\":\"%s\",\"message\":\"%s\",\"world\":\"%s\",\"timestamp\":%d}",
            playerUuid, escape(playerName), escape(message), escape(worldName), System.currentTimeMillis()
        ));
    }

    public static void dispatchFault(String component, String details, String trace) {
        dispatchJson(String.format(
            "{\"type\":\"fault\",\"component\":\"%s\",\"details\":\"%s\",\"trace\":\"%s\",\"timestamp\":%d}",
            escape(component), escape(details), escape(trace), System.currentTimeMillis()
        ));
    }

    public static void dispatchTelemetry(double tps, double mspt, int onlinePlayers, long freeMemoryMb) {
        dispatchJson(String.format(
            "{\"type\":\"telemetry\",\"tps\":%.2f,\"mspt\":%.2f,\"players\":%d,\"free_ram_mb\":%d,\"timestamp\":%d}",
            tps, mspt, onlinePlayers, freeMemoryMb, System.currentTimeMillis()
        ));
    }

    private static void dispatchJson(String jsonPayload) {
        ASYNC_DISPATCHER.submit(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(ingestUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("User-Agent", "DrakesPurpur-Kernel/26.2");
                if (!bearerToken.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
                }
                conn.setConnectTimeout(1500);
                conn.setReadTimeout(1500);
                conn.setDoOutput(true);

                byte[] payloadBytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payloadBytes);
                }
                int responseCode = conn.getResponseCode();
                if (responseCode >= 400) {
                    LOGGER.fine("[SaoriBridge] Non-200 response from SAORI ingest: " + responseCode);
                }
            } catch (Throwable t) {
                LOGGER.fine("[SaoriBridge] Ingest endpoint offline or unreachable: " + t.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
