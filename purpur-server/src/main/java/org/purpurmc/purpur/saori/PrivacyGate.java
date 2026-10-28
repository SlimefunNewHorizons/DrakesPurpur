package org.purpurmc.purpur.saori;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PrivacyGate — unica barrera del motor entre lo privado (mensajes directos, chat de equipo,
 * comandos) y SAORI/Discord. Sin dependencias de Bukkit: se prueba con JUnit puro.
 * Regla: ante la duda, se bloquea.
 */
public final class PrivacyGate {

    /** Eventos publicos permitidos por defecto. */
    public static final Set<String> DEFAULT_PUBLIC_KINDS = Set.of(
        "chat", "join", "quit", "death", "advancement", "log", "startup", "telemetry", "fault", "ping",
        "automation", "digest");

    /** Nunca se pueden habilitar, ni por configuracion. */
    private static final Set<String> HARD_DENY_KINDS = Set.of(
        "command", "cmd", "whisper", "msg", "tell", "mail", "teamchat", "team_chat", "party", "social_spy");

    private static final Set<String> PRIVATE_COMMANDS = Set.of(
        "msg", "tell", "w", "whisper", "r", "reply", "m", "t", "pm", "mail",
        "emsg", "etell", "ewhisper", "er", "ereply", "dm", "mp", "pmsg",
        "tc", "teamchat", "tchat", "ac", "allychat", "pc", "pchat", "partychat",
        "party", "gc", "gchat", "guildchat", "cc", "clanchat", "socialspy", "togglechat");

    private static final Set<String> TEAM_ROOTS = Set.of(
        "team", "teams", "clan", "clans", "party", "guild", "gang", "ally", "faction", "f", "bt");
    private static final Set<String> TEAM_CHAT_SUBS = Set.of(
        "chat", "c", "msg", "message", "say", "ch", "talk");

    private static final Pattern PRIVATE_LOG = Pattern.compile(
        "(?i)issued server command"
        + "|\\[(?:team|party|clan|guild|ally|allies)[ _-]?chat\\]"
        + "|\\b(?:team|party|clan|guild|ally)[ _-]?chat\\b"
        + "|social ?spy"
        + "|whispers to you"
        + "|\\[[^\\]]{1,40} (?:->|=>|→) [^\\]]{1,40}\\]"
        + "|\\bme (?:->|=>|→) \\S+"
        + "|\\[(?:msg|pm|dm|mail)\\]");

    private final Set<String> allowedKinds;
    private final List<String> teamChatPrefixes;
    private final List<Pattern> extraLogPatterns;
    private final Set<String> extraPrivateCommands;

    public PrivacyGate() {
        this(DEFAULT_PUBLIC_KINDS, List.of("!", "?"), List.of(), List.of());
    }

    public PrivacyGate(Set<String> allowedKinds, List<String> teamChatPrefixes,
                       List<String> extraLogRegex, List<String> extraPrivateCommands) {
        Set<String> k = new HashSet<>();
        for (String s : allowedKinds) {
            String n = s.toLowerCase(Locale.ROOT).trim();
            if (!HARD_DENY_KINDS.contains(n)) k.add(n);
        }
        this.allowedKinds = Set.copyOf(k);
        this.teamChatPrefixes = List.copyOf(teamChatPrefixes);
        this.extraLogPatterns = extraLogRegex.stream().map(r -> Pattern.compile(r)).toList();
        Set<String> c = new HashSet<>();
        for (String s : extraPrivateCommands) c.add(s.toLowerCase(Locale.ROOT).replaceFirst("^/", "").trim());
        this.extraPrivateCommands = Set.copyOf(c);
    }

    public boolean isAllowedKind(String kind) {
        return kind != null && allowedKinds.contains(kind.toLowerCase(Locale.ROOT));
    }

    /** true si la linea (con o sin '/') es un comando privado, aunque venga anidado en /execute. */
    public boolean isPrivateCommand(String line) {
        if (line == null) return false;
        String s = line.strip();
        if (s.startsWith("/")) s = s.substring(1).stripLeading();
        // /execute ... run <cmd>: evalua el comando final
        String low = s.toLowerCase(Locale.ROOT);
        int run = low.lastIndexOf(" run ");
        if (run >= 0 && (low.startsWith("execute") || low.contains(":execute"))) {
            return isPrivateCommand(s.substring(run + 5));
        }
        String[] parts = low.split("\\s+", 3);
        String cmd = parts[0];
        int colon = cmd.indexOf(':');
        if (colon >= 0) cmd = cmd.substring(colon + 1); // essentials:msg, betterteams:tc, minecraft:tell
        if (PRIVATE_COMMANDS.contains(cmd) || extraPrivateCommands.contains(cmd)) return true;
        if (TEAM_ROOTS.contains(cmd) && parts.length > 1) {
            return TEAM_CHAT_SUBS.contains(parts[1]);
        }
        return false;
    }

    /** Mensaje de chat que en realidad es de equipo/aliados (prefijo) o un comando colado. */
    public boolean isPrivateChat(String message) {
        if (message == null) return true;
        String m = message.strip();
        if (m.isEmpty()) return true;
        for (String p : teamChatPrefixes) {
            if (!p.isEmpty() && m.startsWith(p)) return true;
        }
        return m.startsWith("/");
    }

    public boolean isPrivateLogLine(String line) {
        if (line == null) return true;
        if (PRIVATE_LOG.matcher(line).find()) return true;
        for (Pattern p : extraLogPatterns) {
            if (p.matcher(line).find()) return true;
        }
        // linea cruda que contiene un comando privado tras un marcador tipico
        int i = line.indexOf("/");
        while (i >= 0) {
            if (i + 1 < line.length() && Character.isLetter(line.charAt(i + 1))
                && (i == 0 || Character.isWhitespace(line.charAt(i - 1)) || line.charAt(i - 1) == ':')
                && isPrivateCommand(line.substring(i))) {
                return true;
            }
            i = line.indexOf("/", i + 1);
        }
        return false;
    }

    /** Decision unica: puede este texto salir hacia SAORI con este tipo de evento? */
    public boolean allow(String kind, String text) {
        if (!isAllowedKind(kind)) return false;
        if (text == null) return true;
        String k = kind.toLowerCase(Locale.ROOT);
        if (!k.equals("chat") && isPrivateCommand(text)) return false;
        switch (k) {
            case "chat":
                return !isPrivateChat(text);
            case "log":
            case "startup":
            case "fault":
            case "automation":
            case "digest":
                return !isPrivateLogLine(text);
            default:
                return !text.strip().startsWith("/") && !isPrivateCommand(text);
        }
    }
}
