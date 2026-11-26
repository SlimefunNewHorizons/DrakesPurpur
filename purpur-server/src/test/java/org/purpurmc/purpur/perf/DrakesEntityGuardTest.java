package org.purpurmc.purpur.perf;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DrakesEntityGuardTest {

    @Test
    void javaReferenceMatchesInvariantsForProtectedAndItemFrames() {
        int[] cx = {3, 3, 3, 3, 3, 3};
        int[] cz = {-2, -2, -2, -2, -2, -2};
        byte[] kd = {
            DrakesEntityGuard.KIND_MOB,
            DrakesEntityGuard.KIND_MOB,
            DrakesEntityGuard.KIND_MOB,
            DrakesEntityGuard.KIND_DISPOSABLE,
            DrakesEntityGuard.KIND_DISPOSABLE,
            DrakesEntityGuard.KIND_ITEM_FRAME
        };
        byte[] pr = {0, 0, 1, 1, 0, 0};
        byte[] act = new byte[6];
        int[] hs = new int[12];

        int count = DrakesEntityGuard.analyzeJava(cx, cz, kd, pr, 1, 0, 0, act, hs);
        assertEquals(1, count);
        assertEquals(DrakesEntityGuard.ACTION_KEEP, act[0]);
        assertEquals(DrakesEntityGuard.ACTION_THROTTLE_AI, act[1]);
        assertEquals(DrakesEntityGuard.ACTION_KEEP, act[2]); // protected mob
        assertEquals(DrakesEntityGuard.ACTION_KEEP, act[3]); // protected disposable (Slimefun item)
        assertEquals(DrakesEntityGuard.ACTION_KEEP, act[5]); // ItemFrame NEVER deleted
        assertEquals(3, hs[0]);
        assertEquals(-2, hs[1]);
        assertEquals(6, hs[2]);
        assertEquals(1, hs[5]); // frames count reported
    }
}
