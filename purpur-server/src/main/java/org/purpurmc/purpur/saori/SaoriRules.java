package org.purpurmc.purpur.saori;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Logica pura (sin Bukkit, sin reloj propio) de las reglas de SaoriAutomations: testeable con JUnit. */
public final class SaoriRules {
    private SaoriRules() {}

    /** Anti-spam: dispara como maximo una vez cada cooldownMs. */
    public static final class Cooldown {
        private final long cooldownMs;
        private long last = Long.MIN_VALUE;

        public Cooldown(long cooldownMs) {
            this.cooldownMs = cooldownMs;
        }

        public synchronized boolean tryFire(long now) {
            if (last != Long.MIN_VALUE && now - last < cooldownMs) return false;
            last = now;
            return true;
        }
    }

    /** Condicion sostenida: true cuando el umbral se cumple de forma continua durante sustainMs. */
    public static final class Sustained {
        private final double threshold;
        private final boolean above;
        private final long sustainMs;
        private long since = -1;

        public Sustained(double threshold, boolean above, long sustainMs) {
            this.threshold = threshold;
            this.above = above;
            this.sustainMs = sustainMs;
        }

        public boolean update(long now, double value) {
            boolean bad = above ? value > threshold : value < threshold;
            if (!bad) {
                since = -1;
                return false;
            }
            if (since < 0) since = now;
            return now - since >= sustainMs;
        }
    }

    /** Agrupa excepciones repetidas por firma; solo la 1a ocurrencia pasa, el resto se resume. */
    public static final class ErrorGrouper {
        public record Group(String signature, int count, long firstSeen) {}

        private final Map<String, int[]> counts = new LinkedHashMap<>();
        private final Map<String, Long> first = new HashMap<>();
        private final int maxGroups;

        public ErrorGrouper(int maxGroups) {
            this.maxGroups = maxGroups;
        }

        /** @return numero de ocurrencias de esta firma en la ventana (1 = primera). */
        public synchronized int record(String signature, long now) {
            int[] c = counts.get(signature);
            if (c == null) {
                if (counts.size() >= maxGroups) return Integer.MAX_VALUE; // desborde: se cuenta como repetido
                c = new int[1];
                counts.put(signature, c);
                first.put(signature, now);
            }
            return ++c[0];
        }

        /** Devuelve los grupos con repeticiones (count > 1) y reinicia la ventana. */
        public synchronized List<Group> flush() {
            List<Group> out = new ArrayList<>();
            counts.forEach((sig, c) -> {
                if (c[0] > 1) out.add(new Group(sig, c[0], first.get(sig)));
            });
            counts.clear();
            first.clear();
            out.sort((a, b) -> Integer.compare(b.count, a.count));
            return out;
        }

        /** Firma estable: logger + 1a linea sin numeros + 3 frames. */
        public static String signature(String logger, String message, StackTraceElement[] trace) {
            String m = message == null ? "" : message.lines().findFirst().orElse("");
            m = m.replaceAll("[0-9]+", "#");
            if (m.length() > 160) m = m.substring(0, 160);
            StringBuilder sb = new StringBuilder(logger == null ? "" : logger).append('|').append(m);
            if (trace != null) {
                for (int i = 0; i < Math.min(3, trace.length); i++) sb.append('|').append(trace[i]);
            }
            return sb.toString();
        }
    }

    /** Tiempos de enable por plugin. */
    public static final class BootProfiler {
        private final Map<String, Long> start = new HashMap<>();
        private final Map<String, Long> durations = new LinkedHashMap<>();

        public synchronized void start(String plugin, long now) {
            start.put(plugin, now);
        }

        /** @return duracion en ms, o -1 si no hubo inicio registrado. */
        public synchronized long end(String plugin, long now) {
            Long s = start.remove(plugin);
            if (s == null) return -1;
            long d = Math.max(0, now - s);
            durations.put(plugin, d);
            return d;
        }

        public synchronized List<Map.Entry<String, Long>> slowest(int n) {
            List<Map.Entry<String, Long>> l = new ArrayList<>(durations.entrySet());
            l.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            return l.subList(0, Math.min(n, l.size()));
        }

        public synchronized List<Map.Entry<String, Long>> overThreshold(long ms) {
            return durations.entrySet().stream().filter(e -> e.getValue() > ms).toList();
        }
    }

    /** Rafaga: >= max mensajes del mismo jugador en windowMs (con cooldown por jugador). */
    public static final class FloodDetector {
        private final int max;
        private final long windowMs;
        private final long cooldownMs;
        private final Map<String, java.util.ArrayDeque<Long>> windows = new HashMap<>();
        private final Map<String, Long> lastFired = new HashMap<>();

        public FloodDetector(int max, long windowMs, long cooldownMs) {
            this.max = max;
            this.windowMs = windowMs;
            this.cooldownMs = cooldownMs;
        }

        public synchronized boolean record(String player, long now) {
            if (windows.size() > 2000) {
                windows.clear();
                lastFired.clear();
            }
            var q = windows.computeIfAbsent(player, k -> new java.util.ArrayDeque<>());
            q.addLast(now);
            while (!q.isEmpty() && now - q.peekFirst() > windowMs) q.pollFirst();
            if (q.size() >= max) {
                Long lf = lastFired.get(player);
                if (lf == null || now - lf >= cooldownMs) {
                    lastFired.put(player, now);
                    return true;
                }
            }
            return false;
        }
    }

    /** Cola que crece: true si en N muestras seguidas crece y supera un minimo. */
    public static final class GrowthDetector {
        private final int samples;
        private final long min;
        private long prev = -1;
        private int rising;

        public GrowthDetector(int samples, long min) {
            this.samples = samples;
            this.min = min;
        }

        public synchronized boolean update(long value) {
            if (prev >= 0 && value > prev) rising++;
            else if (value <= prev) rising = 0;
            prev = value;
            return rising >= samples && value >= min;
        }
    }

    /** Contadores del digest publico. */
    public static final class DigestCounter {
        private final Map<String, Integer> counts = new LinkedHashMap<>();

        public synchronized void add(String kind) {
            counts.merge(kind, 1, Integer::sum);
        }

        public synchronized Map<String, Integer> takeAndReset() {
            Map<String, Integer> out = new LinkedHashMap<>(counts);
            counts.clear();
            return out;
        }
    }
}
