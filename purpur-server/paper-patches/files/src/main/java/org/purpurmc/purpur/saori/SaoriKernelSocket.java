package org.purpurmc.purpur.saori;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SaoriKernelSocket — Direct Zero-Lag Bridge between DrakesPurpur and SAORI SRE
 * Eradicates log scraping and heavy external Discord bots.
 * Author: Jack (JackStar6677-1) - Star Systems & DrakesCraft Labs
 */
public final class SaoriKernelSocket {
    private static final Logger LOGGER = Logger.getLogger("SaoriKernelBridge");
    private static final String SAORI_INGEST_URL = "http://127.0.0.1:8089/api/v1/mc/event";
    private static final ExecutorService ASYNC_DISPATCHER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Saori-Kernel-Dispatcher");
        t.setDaemon(true);
        return t;
    });

    private SaoriKernelSocket() {}

    public static void dispatchChat(String playerUuid, String playerName, String message, String worldName) {
        dispatchJson(String.format(
            "{"type":"chat","uuid":"%s","player":"%s","message":"%s","world":"%s","timestamp":%d}",
            playerUuid, escape(playerName), escape(message), escape(worldName), System.currentTimeMillis()
        ));
    }

    public static void dispatchFault(String component, String details, String trace) {
        dispatchJson(String.format(
            "{"type":"fault","component":"%s","details":"%s","trace":"%s","timestamp":%d}",
            escape(component), escape(details), escape(trace), System.currentTimeMillis()
        ));
    }

    public static void dispatchTelemetry(double tps, double mspt, int onlinePlayers, long freeMemoryMb) {
        dispatchJson(String.format(
            "{"type":"telemetry","tps":%.2f,"mspt":%.2f,"players":%d,"free_ram_mb":%d,"timestamp":%d}",
            tps, mspt, onlinePlayers, freeMemoryMb, System.currentTimeMillis()
        ));
    }

    private static void dispatchJson(String jsonPayload) {
        ASYNC_DISPATCHER.submit(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(SAORI_INGEST_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("User-Agent", "DrakesPurpur-Kernel/26.2");
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
        return s.replace("\", "\\").replace(""", "\"").replace("
", "\n").replace("", "\r");
    }
}
