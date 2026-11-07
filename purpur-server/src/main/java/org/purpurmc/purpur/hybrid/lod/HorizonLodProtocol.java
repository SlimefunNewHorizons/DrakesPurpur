package org.purpurmc.purpur.hybrid.lod;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Codec del canal {@code horizon:lod} version 1 (terreno lejano muestreado por el SERVIDOR para clientes Horizon),
 * escrito a partir del codigo de Horizon (MIT) {@code crates/horizon-client/src/lod_net.rs} @ 41f822b. Es SOLO el
 * formato de cable y sus limites: NO esta conectado a ninguna conexion (ver docs/hybrid/DRAKES_LOD.md). No se envia
 * nada a ningun cliente, y mucho menos a clientes que no hayan negociado soporte.
 * <p>
 * Convenciones (las de Minecraft, confirmadas en {@code horizon-protocol/src/buf.rs}): VarInt de 7 bits LE, enteros big-endian,
 * cadenas = VarInt de longitud + UTF-8. Los ids de estado de bloque son los del registro de 26.3 (Horizon); el servidor 26.2
 * necesita una tabla de traduccion (aqui son opacos).
 */
public final class HorizonLodProtocol {
    public static final String CHANNEL = "horizon:lod";
    public static final String PROPERTY = "horizon-lod-distance";   // server.properties de Horizon; 0 = apagado
    public static final int VERSION = 1;
    public static final int MAX_DISTANCE = 1024;
    public static final int IN_FLIGHT = 8;
    public static final int PER_SECOND = 64;
    public static final int SIDE = 16;
    public static final int SLACK = 8;
    public static final int MAX_BIOMES = 256;
    public static final int MAX_BIOME_NAME = 256;
    public static final int MAX_SPANS = 16 * 1024;
    public static final int MAX_REPLY = 512 * 1024;
    public static final int HEIGHT_MIN = -2048, HEIGHT_MAX = 4096;

    private HorizonLodProtocol() {}

    // ---- bytes -------------------------------------------------------------------------------------
    static final class W {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();

        W varint(int v) {
            int x = v;
            while ((x & ~0x7F) != 0) {
                o.write((x & 0x7F) | 0x80);
                x >>>= 7;
            }
            o.write(x);
            return this;
        }

        W u8(int v) { o.write(v); return this; }
        W bool(boolean b) { o.write(b ? 1 : 0); return this; }
        W i16(int v) { o.write(v >>> 8); o.write(v); return this; }
        W i32(int v) { o.write(v >>> 24); o.write(v >>> 16); o.write(v >>> 8); o.write(v); return this; }
        W str(String s) { byte[] b = s.getBytes(StandardCharsets.UTF_8); varint(b.length); o.write(b, 0, b.length); return this; }
        W raw(byte[] b) { o.write(b, 0, b.length); return this; }
        byte[] bytes() { return o.toByteArray(); }
    }

    static final class R {
        final ByteBuffer b;

        R(byte[] d) { b = ByteBuffer.wrap(d); }

        int remaining() { return b.remaining(); }
        int u8() { return b.get() & 0xFF; }
        boolean bool() { return u8() != 0; }
        int i16() { return b.getShort(); }
        int u16() { return b.getShort() & 0xFFFF; }
        int i32() { return b.getInt(); }

        int varint() {
            int v = 0;
            for (int i = 0; i < 5; i++) {
                int x = u8();
                v |= (x & 0x7F) << (7 * i);
                if ((x & 0x80) == 0) return v;
            }
            throw new IllegalArgumentException("varint demasiado largo");
        }

        String str(int max) {
            int n = varint();
            if (n < 0 || n > max * 4) throw new IllegalArgumentException("cadena demasiado larga");
            byte[] d = new byte[n];
            b.get(d);
            String s = new String(d, StandardCharsets.UTF_8);
            if (s.length() > max) throw new IllegalArgumentException("cadena demasiado larga");
            return s;
        }
    }

    // ---- Offer: servidor -> cliente Horizon, fase de configuracion ---------------------------------
    public record Offer(int distance, int inFlight, int perSecond) {
        public static Offer of(int distance) {
            return new Offer(Math.min(Math.max(distance, 0), MAX_DISTANCE), IN_FLIGHT, PER_SECOND);
        }

        public byte[] encode() {
            return new W().varint(VERSION).varint(distance).varint(inFlight).varint(perSecond).bytes();
        }

        /** null si no sirve nada o habla otra version (igual que el cliente de Horizon). */
        public static Offer decode(byte[] data) {
            try {
                R r = new R(data);
                if (r.varint() != VERSION) return null;
                int d = Math.min(r.varint(), MAX_DISTANCE), f = Math.min(r.varint(), 64), p = r.varint();
                return d > 0 && f > 0 && p > 0 ? new Offer(d, f, p) : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    // ---- Request: cliente -> servidor ---------------------------------------------------------------
    public record Request(int id, int x, int z, int stride, boolean approximate) {
        public byte[] encode() {
            return new W().varint(id).i32(x).i32(z).varint(stride).bool(approximate).bytes();
        }

        public static Request decode(byte[] data) {
            R r = new R(data);
            Request q = new Request(r.varint(), r.i32(), r.i32(), r.varint(), r.bool());
            if (r.remaining() > 0) throw new IllegalArgumentException("bytes sobrantes");
            return q;
        }

        /**
         * Si el servidor sirve el tile a un jugador en (px,pz) con {@code distance} chunks permitidos: tile alineado, stride potencia de
         * dos no mayor que el tile mas grueso a esa distancia, y punto mas cercano dentro de (distance+SLACK) chunks.
         */
        public boolean allowed(int distance, double px, double pz) {
            int coarsest = distance <= 1 ? 1 : Math.min(Integer.highestOneBit(distance - 1) << 1, MAX_DISTANCE);
            if (distance == 0 || stride < 1 || stride > coarsest || Integer.bitCount(stride) != 1) return false;
            int size = stride * SIDE;
            if (Math.floorMod(x, size) != 0 || Math.floorMod(z, size) != 0) return false;
            double dx = gap(px, x, size), dz = gap(pz, z, size);
            double reach = (distance + SLACK) * 16.0;
            return dx * dx + dz * dz <= reach * reach;
        }

        private static double gap(double c, int lo, int size) {
            return Math.max(0.0, Math.max(lo - c, c - lo - size));
        }
    }

    // ---- Reply: servidor -> cliente -------------------------------------------------------------------
    public record Span(int min, int max, int state, String biome, int light) {}

    public record Water(int height, int state) {}

    public record Surface(int height, int state, Water water, String biome) {}

    public record Column(Surface surface, List<Span> spans) {}

    /** Respuesta con las 256 columnas; devuelve un rechazo (1) si excede los limites o no es un tile entero. */
    public static byte[] encodeReply(int id, List<Column> columns) {
        if (columns == null || columns.size() != SIDE * SIDE) return refusal(id);
        int spans = 0;
        for (Column c : columns) spans += c.spans().size();
        if (spans > MAX_SPANS) return refusal(id);
        Map<String, Integer> palette = new LinkedHashMap<>();
        W body = new W().varint(columns.size());
        for (Column c : columns) {
            Surface s = c.surface();
            if (s != null) {
                body.bool(true).i32(s.height()).varint(s.state());
                if (s.water() != null) body.bool(true).i32(s.water().height()).varint(s.water().state());
                else body.bool(false);
                body.varint(local(palette, s.biome()));
            } else {
                body.bool(false);
            }
            body.varint(c.spans().size());
            for (Span sp : c.spans()) body.i16(sp.min()).i16(sp.max()).varint(sp.state()).varint(local(palette, sp.biome())).i16(sp.light());
        }
        if (palette.size() > MAX_BIOMES) return refusal(id);
        W w = new W().varint(id).u8(0).varint(palette.size());
        for (String b : palette.keySet()) w.str(b.length() <= MAX_BIOME_NAME ? b : "minecraft:plains");
        w.raw(body.bytes());
        byte[] out = w.bytes();
        return out.length > MAX_REPLY ? refusal(id) : out;
    }

    public static byte[] refusal(int id) {
        return new W().varint(id).u8(1).bytes();
    }

    private static int local(Map<String, Integer> palette, String biome) {
        Integer i = palette.get(biome);
        if (i == null) {
            i = palette.size();
            palette.put(biome, i);
        }
        return i;
    }

    /** Decodifica y valida como el cliente (orden de spans, rangos, tamaños); null = rechazo. Lanza si el contenido es invalido. */
    public static List<Column> decodeReply(byte[] data, int states) {
        if (data.length > MAX_REPLY) throw new IllegalArgumentException("respuesta demasiado grande");
        R r = new R(data);
        r.varint();
        if (r.u8() != 0) return null;
        int n = r.varint();
        if (n < 0 || n > MAX_BIOMES) throw new IllegalArgumentException("demasiados biomas");
        List<String> palette = new ArrayList<>();
        for (int i = 0; i < n; i++) palette.add(r.str(MAX_BIOME_NAME));
        int cols = r.varint();
        if (cols != SIDE * SIDE) throw new IllegalArgumentException("no es un tile entero");
        List<Column> out = new ArrayList<>();
        int total = 0;
        for (int c = 0; c < cols; c++) {
            Surface s = null;
            if (r.bool()) {
                int h = height(r.i32()), st = state(r.varint(), states);
                Water w = r.bool() ? new Water(height(r.i32()), state(r.varint(), states)) : null;
                s = new Surface(h, st, w, palette.get(r.varint()));
            }
            int count = r.varint();
            total += count;
            if (count < 0 || total > MAX_SPANS) throw new IllegalArgumentException("demasiados spans");
            List<Span> spans = new ArrayList<>();
            int floor = HEIGHT_MIN;
            for (int i = 0; i < count; i++) {
                Span sp = new Span(r.i16(), r.i16(), state(r.varint(), states), palette.get(r.varint()), r.u16());
                if (sp.min() < floor || sp.min() >= sp.max() || sp.max() > HEIGHT_MAX) throw new IllegalArgumentException("spans fuera de orden");
                floor = sp.max();
                spans.add(sp);
            }
            out.add(new Column(s, spans));
        }
        return out;
    }

    private static int height(int y) {
        if (y < HEIGHT_MIN || y > HEIGHT_MAX) throw new IllegalArgumentException("altura fuera de rango");
        return y;
    }

    private static int state(int id, int states) {
        if (id < 0 || id >= states) throw new IllegalArgumentException("estado de bloque desconocido");
        return id;
    }

    /** Cubo de fichas (igual que el servidor de Horizon): {@code rate} por segundo, rafaga {@code burst}. */
    public static final class Bucket {
        private double tokens;
        private final double rate, burst;
        private long atNanos;

        public Bucket(double rate, double burst, long nowNanos) {
            this.tokens = burst;
            this.rate = rate;
            this.burst = burst;
            this.atNanos = nowNanos;
        }

        public boolean take(long nowNanos) {
            tokens = Math.min(burst, tokens + Math.max(0, nowNanos - atNanos) / 1e9 * rate);
            atNanos = nowNanos;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
