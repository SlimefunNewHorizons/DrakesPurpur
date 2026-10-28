package org.purpurmc.purpur.saori;

import com.google.gson.JsonObject;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/** Appender Log4j2 en proceso: filtra, aplica PrivacyGate y encola. append() nunca bloquea. */
final class SaoriLogAppender extends AbstractAppender {
    private final Level min;
    private final boolean startup;
    private final PrivacyGate gate;

    SaoriLogAppender(SaoriConfig c) {
        super("SaoriLogAppender", null, null, true, Property.EMPTY_ARRAY);
        this.min = switch (c.logMinLevel.toUpperCase(java.util.Locale.ROOT)) {
            case "INFO" -> Level.INFO;
            case "ERROR" -> Level.ERROR;
            default -> Level.WARN;
        };
        this.startup = c.logStartup;
        this.gate = c.gate;
    }

    @Override
    public void append(LogEvent e) {
        try {
            String logger = e.getLoggerName();
            if (logger != null && (logger.startsWith("Saori") || logger.equals("SaoriLink"))) return; // anti-bucle
            String msg = e.getMessage() == null ? "" : e.getMessage().getFormattedMessage();
            if (msg == null || msg.isEmpty()) return;
            Level lvl = e.getLevel();
            if (!SaoriAutomations.onLog(logger, lvl, msg, e.getThrown())) return; // error repetido: solo en el resumen
            String kind = null;
            if (lvl.isMoreSpecificThan(min)) {
                kind = "log";
            } else if (startup && lvl.isMoreSpecificThan(Level.INFO)
                && (msg.startsWith("Done (") || msg.startsWith("Stopping server") || msg.startsWith("Starting minecraft server"))) {
                kind = "startup";
            }
            if (kind == null) return;
            if (msg.length() > 1000) msg = msg.substring(0, 1000);
            StringBuilder full = new StringBuilder(msg);
            if (e.getThrown() != null) {
                StringWriter sw = new StringWriter();
                e.getThrown().printStackTrace(new PrintWriter(sw));
                String st = sw.toString();
                full.append('\n').append(st, 0, Math.min(st.length(), 1500));
            }
            String text = full.toString();
            // se evalua el log completo (mensaje + traza + logger) antes de salir
            if (!gate.allow(kind, logger + " " + text)) {
                SaoriKernelSocket.countBlocked();
                return;
            }
            JsonObject o = new JsonObject();
            o.addProperty("level", lvl.name());
            o.addProperty("logger", logger == null ? "" : logger);
            o.addProperty("thread", e.getThreadName());
            o.addProperty("line", text);
            o.addProperty("plugin_error", e.getThrown() != null || (logger != null && lvl.isMoreSpecificThan(Level.ERROR)));
            SaoriKernelSocket.emit(kind, null, o);
        } catch (Throwable ignored) {
            // un appender jamas debe afectar al servidor
        }
    }
}
