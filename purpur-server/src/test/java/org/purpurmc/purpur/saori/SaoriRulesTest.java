package org.purpurmc.purpur.saori;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SaoriRulesTest {
    @Test
    void cooldown() {
        var c = new SaoriRules.Cooldown(1000);
        assertTrue(c.tryFire(0));
        assertFalse(c.tryFire(999));
        assertTrue(c.tryFire(1000));
    }

    @Test
    void sustainedLag() {
        var s = new SaoriRules.Sustained(17.0, false, 30_000);
        assertFalse(s.update(0, 12));
        assertFalse(s.update(29_000, 12));
        assertTrue(s.update(30_000, 12));
        assertFalse(s.update(31_000, 20)); // recupera: se reinicia
        assertFalse(s.update(32_000, 12));
    }

    @Test
    void sustainedHeapAbove() {
        var s = new SaoriRules.Sustained(0.9, true, 60_000);
        assertFalse(s.update(0, 0.95));
        assertTrue(s.update(60_000, 0.95));
        assertFalse(s.update(61_000, 0.5));
    }

    @Test
    void errorGrouping() {
        var g = new SaoriRules.ErrorGrouper(10);
        String sig = SaoriRules.ErrorGrouper.signature("Foo", "Error at 12 in 345", null);
        assertEquals(sig, SaoriRules.ErrorGrouper.signature("Foo", "Error at 99 in 1", null));
        assertEquals(1, g.record(sig, 0));
        assertEquals(2, g.record(sig, 1));
        assertEquals(3, g.record(sig, 2));
        g.record("otra", 3);
        var out = g.flush();
        assertEquals(1, out.size());
        assertEquals(3, out.get(0).count());
        assertTrue(g.flush().isEmpty());
    }

    @Test
    void errorGrouperCap() {
        var g = new SaoriRules.ErrorGrouper(1);
        assertEquals(1, g.record("a", 0));
        assertTrue(g.record("b", 0) > 1); // desborde: nunca pasa como "primera"
    }

    @Test
    void bootProfiler() {
        var b = new SaoriRules.BootProfiler();
        b.start("A", 0);
        b.start("B", 0);
        assertEquals(100, b.end("A", 100));
        assertEquals(40_000, b.end("B", 40_000));
        assertEquals(-1, b.end("Z", 5));
        assertEquals("B", b.slowest(5).get(0).getKey());
        assertEquals(1, b.overThreshold(30_000).size());
    }

    @Test
    void floodWithPerPlayerCooldown() {
        var f = new SaoriRules.FloodDetector(5, 8000, 120_000);
        boolean fired = false;
        for (int i = 0; i < 5; i++) fired = f.record("jack", i * 1000);
        assertTrue(fired);
        for (int i = 5; i < 10; i++) assertFalse(f.record("jack", i * 1000)); // cooldown
        assertFalse(f.record("bob", 0));
        var slow = new SaoriRules.FloodDetector(5, 8000, 0);
        for (int i = 0; i < 20; i++) assertFalse(slow.record("x", i * 5000)); // ritmo normal
    }

    @Test
    void growth() {
        var g = new SaoriRules.GrowthDetector(3, 100);
        assertFalse(g.update(10));
        assertFalse(g.update(50));
        assertFalse(g.update(80));
        assertFalse(g.update(90)); // 3 subidas pero < min
        assertTrue(g.update(500));
        assertFalse(g.update(400)); // baja: reinicia
    }

    @Test
    void digest() {
        var d = new SaoriRules.DigestCounter();
        d.add("join");
        d.add("join");
        d.add("death");
        var m = d.takeAndReset();
        assertEquals(2, m.get("join"));
        assertTrue(d.takeAndReset().isEmpty());
    }
}
