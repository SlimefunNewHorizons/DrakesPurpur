package org.purpurmc.purpur.saori;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/** drakes-data/saori.yml. Defaults seguros: desactivada hasta que exista un token valido. */
public final class SaoriConfig {
    private static final Logger LOGGER = Logger.getLogger("SaoriLink");
    static final String PATH = "drakes-data/saori.yml";

    static final String DEFAULT_YML = """
        # SAORI <-> motor (DrakesPurpur). Integracion directa por HTTP loopback; sin base de datos.
        # SAORI queda DESACTIVADA hasta que exista el archivo de token (permisos 600) y enabled: true.
        enabled: false
        # Archivo con el token Bearer (una linea). Debe ser chmod 600; jamas se escribe en logs.
        token-file: drakes-data/saori.token
        # Donde recibe SAORI (solo loopback). /kernel recibe logs/chat/telemetria; /ask recibe /saori <pregunta>.
        endpoint: http://127.0.0.1:8089
        # Prefijo con el que SAORI habla en el chat del juego.
        prefix: "[SAORI]"
        queue-size: 1000
        timeout-ms: 2000
        ask-timeout-seconds: 15
        logs:
          enabled: true
          min-level: WARN        # INFO|WARN|ERROR
          startup: true          # linea "Done (...)" y parada del servidor
          tps-interval-seconds: 60   # 0 = sin telemetria periodica
        chat:
          forward-public: true   # chat publico -> SAORI
          saori-can-speak: true  # SAORI -> chat (accion say/tell)
          events: [join, quit, death, advancement]
          team-chat-prefixes: ["!", "?"]
        inbound:
          enabled: true
          port: 8090             # siempre 127.0.0.1
          # run-command: comandos permitidos por prefijo exacto. VACIA = run-command deshabilitado.
          run-command-allowlist: []
        # Reglas automaticas: SOLO avisan a SAORI (salvo prepare-restart, que anuncia y hace save-all; nunca reinicia).
        automations:
          lag:              {enabled: true, tps-below: 17.0, mspt-above: 50.0, sustain-seconds: 30, cooldown-seconds: 600}
          ping:             {enabled: true, high-ping-ms: 200, min-players: 2, sustain-seconds: 60, cooldown-seconds: 600}
          boot:             {enabled: true, slow-plugin-seconds: 30, top: 5}
          errors:           {enabled: true, window-seconds: 60}
          memory:           {enabled: true, heap-percent: 90, sustain-seconds: 60, gc-pause-ms-per-interval: 2000, cooldown-seconds: 900}
          slimefun-storage: {enabled: true, queue-growth-samples: 4, min-queue: 500, cooldown-seconds: 900}
          digest:           {enabled: true, minutes: 30}
          chat-flood:       {enabled: true, messages: 6, window-seconds: 8, cooldown-seconds: 120}
        privacy:
          allowed-kinds: [chat, join, quit, death, advancement, log, startup, telemetry, fault, ping, automation, digest]
          extra-private-commands: []
          extra-private-log-regex: []
        """;

    public boolean enabled;
    public String tokenFile = "drakes-data/saori.token";
    public String endpoint = "http://127.0.0.1:8089";
    public String prefix = "[SAORI]";
    public int queueSize = 1000;
    public int timeoutMs = 2000;
    public int askTimeoutSeconds = 15;
    public boolean logsEnabled = true;
    public String logMinLevel = "WARN";
    public boolean logStartup = true;
    public int tpsIntervalSeconds = 60;
    public boolean chatForward = true;
    public boolean saoriCanSpeak = true;
    public List<String> chatEvents = List.of("join", "quit", "death", "advancement");
    public boolean inboundEnabled = true;
    public int inboundPort = 8090;
    public List<String> runCommandAllowlist = List.of();
    public PrivacyGate gate = new PrivacyGate();
    public YamlConfiguration yml = new YamlConfiguration();

    /** Token leido y validado; null si no hay o los permisos son laxos. */
    public volatile String token;
    public volatile String tokenProblem = "sin cargar";

    public static SaoriConfig load() {
        SaoriConfig c = new SaoriConfig();
        File f = new File(PATH);
        try {
            if (!f.exists()) {
                File dir = f.getParentFile();
                if (dir != null) dir.mkdirs();
                Files.writeString(f.toPath(), DEFAULT_YML, StandardCharsets.UTF_8);
            }
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            c.yml = y;
            c.enabled = y.getBoolean("enabled", false);
            c.tokenFile = y.getString("token-file", c.tokenFile);
            c.endpoint = y.getString("endpoint", c.endpoint);
            c.prefix = y.getString("prefix", c.prefix);
            c.queueSize = Math.max(10, y.getInt("queue-size", 1000));
            c.timeoutMs = Math.max(200, Math.min(5000, y.getInt("timeout-ms", 2000)));
            c.askTimeoutSeconds = Math.max(2, Math.min(60, y.getInt("ask-timeout-seconds", 15)));
            c.logsEnabled = y.getBoolean("logs.enabled", true);
            c.logMinLevel = y.getString("logs.min-level", "WARN");
            c.logStartup = y.getBoolean("logs.startup", true);
            c.tpsIntervalSeconds = Math.max(0, y.getInt("logs.tps-interval-seconds", 60));
            c.chatForward = y.getBoolean("chat.forward-public", true);
            c.saoriCanSpeak = y.getBoolean("chat.saori-can-speak", true);
            c.chatEvents = y.getStringList("chat.events");
            c.inboundEnabled = y.getBoolean("inbound.enabled", true);
            c.inboundPort = y.getInt("inbound.port", 8090);
            c.runCommandAllowlist = y.getStringList("inbound.run-command-allowlist");
            c.gate = new PrivacyGate(
                Set.copyOf(y.getStringList("privacy.allowed-kinds").isEmpty()
                    ? PrivacyGate.DEFAULT_PUBLIC_KINDS : Set.copyOf(y.getStringList("privacy.allowed-kinds"))),
                y.getStringList("chat.team-chat-prefixes"),
                y.getStringList("privacy.extra-private-log-regex"),
                y.getStringList("privacy.extra-private-commands"));
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[SaoriLink] saori.yml ilegible, SAORI queda desactivada: " + t.getMessage());
            c.enabled = false;
        }
        // La destino SIEMPRE es loopback, diga lo que diga el yml.
        try {
            String host = java.net.URI.create(c.endpoint).getHost();
            if (host == null || !(host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]"))) {
                LOGGER.warning("[SaoriLink] endpoint no es loopback; SAORI desactivada.");
                c.enabled = false;
            }
        } catch (Throwable t) {
            c.enabled = false;
        }
        c.loadToken();
        if (c.token == null) c.enabled = false;
        return c;
    }

    private void loadToken() {
        token = null;
        try {
            Path p = Path.of(tokenFile);
            if (!Files.isRegularFile(p)) {
                tokenProblem = "falta el archivo de token";
                return;
            }
            try {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p);
                if (perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.GROUP_WRITE)
                    || perms.contains(PosixFilePermission.OTHERS_READ) || perms.contains(PosixFilePermission.OTHERS_WRITE)) {
                    tokenProblem = "permisos del token laxos (" + PosixFilePermissions.toString(perms) + "); requiere 600";
                    return;
                }
            } catch (UnsupportedOperationException ignored) {
                // sin POSIX: no se puede comprobar
            }
            String t = Files.readString(p, StandardCharsets.UTF_8).strip();
            if (t.length() < 16 || t.contains("\n")) {
                tokenProblem = "token vacio o demasiado corto (min. 16)";
                return;
            }
            token = t;
            tokenProblem = "ok";
        } catch (IOException e) {
            tokenProblem = "no se pudo leer el token";
        }
    }
}
