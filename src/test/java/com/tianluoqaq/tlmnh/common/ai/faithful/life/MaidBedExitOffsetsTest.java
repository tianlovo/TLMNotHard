package com.tianluoqaq.tlmnh.common.ai.faithful.life;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MaidBedExitOffsetsTest {

    @Test
    public void northBedWithSouthLookTriesItsEastSideBeforeTheOldFixedWestExit() {
        int[][] actual = MaidBedExitOffsets.ordered(2, 0, 1);
        int[][] expected = { { 1, 0 }, { 1, 1 }, { 1, 2 }, { 0, 2 }, { -1, 2 }, { -1, 1 }, { -1, 0 }, { -1, -1 },
            { 0, -1 }, { 1, -1 }, { 0, 0 }, { 0, 1 } };
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) assertArrayEquals(expected[i], actual[i]);
    }

    @Test
    public void currentLookAndActualBedFacingDetermineTheFirstCandidate() {
        assertArrayEquals(new int[] { -1, 0 }, MaidBedExitOffsets.ordered(2, 1, 0)[0]);
        assertArrayEquals(new int[] { 1, 0 }, MaidBedExitOffsets.ordered(2, -1, 0)[0]);
        assertArrayEquals(new int[] { -1, 0 }, MaidBedExitOffsets.ordered(0, 0, 1)[0]);
        assertArrayEquals(new int[] { 0, -1 }, MaidBedExitOffsets.ordered(1, 0, 1)[0]);
        assertArrayEquals(new int[] { 0, -1 }, MaidBedExitOffsets.ordered(3, 0, 1)[0]);
        assertEquals(0, MaidBedExitOffsets.ordered(2, Float.NaN, 1).length);
    }
}
