package org.purpurmc.purpur.perf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DrakesCargoBatchTest {

    @Test
    void hashBatchRespectsIgnoreAmountAndMetaFlags() {
        int[] mats = {10, 10, 10, 11};
        int[] amts = {1, 64, 64, 1};
        long[] nbts = {0xABCDL, 0xABCDL, 0x9999L, 0xABCDL};
        byte[] flags = {
            DrakesCargoBatch.FLAG_IGNORE_AMOUNT,
            DrakesCargoBatch.FLAG_IGNORE_AMOUNT,
            DrakesCargoBatch.FLAG_IGNORE_AMOUNT,
            DrakesCargoBatch.FLAG_IGNORE_AMOUNT
        };
        long[] out = new long[4];

        int n = DrakesCargoBatch.hashBatchJava(mats, amts, nbts, flags, out);
        assertEquals(4, n);
        assertEquals(out[0], out[1], "ignore_amount must match across stack sizes");
        assertNotEquals(out[0], out[2], "different NBT hash must differ");
        assertNotEquals(out[0], out[3], "different material must differ");

        byte[] noMeta = {
            DrakesCargoBatch.FLAG_IGNORE_AMOUNT | DrakesCargoBatch.FLAG_IGNORE_META,
            DrakesCargoBatch.FLAG_IGNORE_AMOUNT | DrakesCargoBatch.FLAG_IGNORE_META,
            DrakesCargoBatch.FLAG_IGNORE_AMOUNT | DrakesCargoBatch.FLAG_IGNORE_META,
            DrakesCargoBatch.FLAG_IGNORE_AMOUNT | DrakesCargoBatch.FLAG_IGNORE_META
        };
        long[] out2 = new long[4];
        DrakesCargoBatch.hashBatchJava(mats, amts, nbts, noMeta, out2);
        assertEquals(out2[0], out2[2]);
    }

    @Test
    void routeBatchOrdersByPriorityAndRespectsCapacity() {
        long[] srcKeys = {100L, 200L};
        int[] srcAmts = {50, 10};
        long[] dstKeys = {100L, 100L, 200L};
        int[] dstCaps = {20, 40, 5};
        int[] dstPrio = {1, 10, 5};

        int[] os = new int[8];
        int[] od = new int[8];
        int[] oa = new int[8];

        int count = DrakesCargoBatch.routeBatchJava(srcKeys, srcAmts, dstKeys, dstCaps, dstPrio, os, od, oa);
        assertEquals(3, count);
        assertArrayEquals(new int[]{0, 0, 1}, new int[]{os[0], os[1], os[2]});
        assertArrayEquals(new int[]{1, 0, 2}, new int[]{od[0], od[1], od[2]});
        assertArrayEquals(new int[]{40, 10, 5}, new int[]{oa[0], oa[1], oa[2]});
    }
}
