package org.purpurmc.purpur.saori;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PrivacyGateTest {
    private final PrivacyGate gate = new PrivacyGate();

    private static final List<String> PRIVATE_COMMANDS = List.of(
        "/msg Bob secreto", "/tell Bob hola", "/w Bob hola", "/whisper Bob hola", "/r ok", "/reply ok",
        "/m Bob x", "/t Bob x", "/pm Bob x", "/mail send Bob x", "/MSG Bob x", "msg Bob sin slash",
        "/essentials:msg Bob x", "/minecraft:tell Bob x", "/essentials:r x", "/emsg Bob x", "/etell Bob x",
        "/ewhisper Bob x", "/ereply x", "/tc hola equipo", "/teamchat hola", "/betterteams:tc hola",
        "/team chat hola", "/team c hola", "/teams chat hola", "/party hola", "/party chat hola",
        "/clan chat hola", "/clan msg hola", "/ac aliados", "/allychat aliados", "/pchat x",
        "/execute as @a run msg Bob x", "/minecraft:execute run tell Bob x", "/socialspy",
        "/  msg Bob espacios");

    private static final List<String> PRIVATE_LOG_LINES = List.of(
        "[12:00:01 INFO]: Jack issued server command: /msg Bob secreto",
        "[12:00:01 INFO]: Jack issued server command: /tc hola",
        "[12:00:01 INFO]: Jack issued server command: /spawn",
        "[12:00:02 INFO]: [BetterTeams] [TeamChat] Jack: hola equipo",
        "[12:00:02 INFO]: [Team Chat] Jack: hola",
        "[12:00:02 INFO]: [SocialSpy] Jack -> Bob: hola",
        "[12:00:02 INFO]: [Jack -> Bob] hola",
        "[12:00:02 INFO]: Jack whispers to you: hola",
        "[12:00:02 INFO]: [PartyChat] Jack: x",
        "[12:00:02 INFO]: Jack: /r gracias");

    @Test
    void privateCommandsAreBlocked() {
        for (String c : PRIVATE_COMMANDS) {
            assertTrue(gate.isPrivateCommand(c), c);
            assertFalse(gate.allow("telemetry", c), c);
            assertFalse(gate.allow("fault", c), c);
        }
    }

    @Test
    void privateLogLinesAreBlocked() {
        for (String l : PRIVATE_LOG_LINES) {
            assertTrue(gate.isPrivateLogLine(l), l);
            assertFalse(gate.allow("log", l), l);
        }
    }

    @Test
    void publicStuffPasses() {
        assertTrue(gate.allow("chat", "hola a todos"));
        assertTrue(gate.allow("join", "Jack"));
        assertTrue(gate.allow("quit", "Jack"));
        assertTrue(gate.allow("death", "Jack fue asesinado"));
        assertTrue(gate.allow("advancement", "Jack hizo el logro X"));
        assertTrue(gate.allow("log", "[12:00:01 WARN]: Can't keep up! Is the server overloaded?"));
        assertTrue(gate.allow("log", "[12:00:01 ERROR]: Error occurred while enabling Foo v1 (Is it up to date?)"));
        assertFalse(gate.isPrivateCommand("/spawn"));
        assertFalse(gate.isPrivateCommand("/home casa"));
        assertFalse(gate.isPrivateCommand("/team create Foo"));
        assertFalse(gate.isPrivateCommand("/me baila"));
    }

    @Test
    void teamChatPrefixesAndSlashChatAreBlocked() {
        assertFalse(gate.allow("chat", "!solo equipo"));
        assertFalse(gate.allow("chat", "?solo aliados"));
        assertFalse(gate.allow("chat", "/msg Bob x"));
        assertFalse(gate.allow("chat", "   "));
    }

    @Test
    void unknownAndHardDeniedKindsAreRejected() {
        assertFalse(gate.allow("whisper", "hola"));
        assertFalse(gate.allow("command", "spawn"));
        assertFalse(gate.allow("loquesea", "x"));
        PrivacyGate wide = new PrivacyGate(Set.of("chat", "whisper", "teamchat", "command"), List.of("!"), List.of(), List.of());
        assertFalse(wide.isAllowedKind("whisper"));
        assertFalse(wide.isAllowedKind("teamchat"));
        assertFalse(wide.isAllowedKind("command"));
        assertTrue(wide.isAllowedKind("chat"));
        assertFalse(wide.isAllowedKind("join"));
    }

    @Test
    void configurableExtras() {
        PrivacyGate g = new PrivacyGate(PrivacyGate.DEFAULT_PUBLIC_KINDS, List.of("!"), List.of("(?i)secretplugin"), List.of("/susurro"));
        assertTrue(g.isPrivateCommand("/susurro Bob x"));
        assertTrue(g.isPrivateLogLine("[INFO]: SecretPlugin dijo algo"));
        assertTrue(g.allow("chat", "?pregunta normal"));
    }
}
