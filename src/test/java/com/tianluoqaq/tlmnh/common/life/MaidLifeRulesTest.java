package com.tianluoqaq.tlmnh.common.life;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;

public class MaidLifeRulesTest {

    @Test
    public void dayShiftWakesAtDawnAndStartsRestAt16000() {
        assertFalse(MaidLifeRules.isRest(MaidSchedule.DAY, 15999));
        assertTrue(MaidLifeRules.isRest(MaidSchedule.DAY, 16000));
        assertTrue(MaidLifeRules.isRest(MaidSchedule.DAY, 23999));
        assertFalse(MaidLifeRules.isRest(MaidSchedule.DAY, 24000));
        assertTrue(MaidLifeRules.isRest(MaidSchedule.DAY, -1));
    }

    @Test
    public void nightShiftRestEndsAt8000AndAllDayNeverSleeps() {
        assertTrue(MaidLifeRules.isRest(MaidSchedule.NIGHT, 0));
        assertTrue(MaidLifeRules.isRest(MaidSchedule.NIGHT, 7999));
        assertFalse(MaidLifeRules.isRest(MaidSchedule.NIGHT, 8000));
        assertFalse(MaidLifeRules.isRest(MaidSchedule.NIGHT, 23999));
        for (int time : new int[] { 0, 7999, 8000, 15999, 16000, 23999, 24000 })
            assertFalse(MaidLifeRules.isRest(MaidSchedule.ALL, time));
        assertEquals(MaidLifeRules.Activity.IDLE, MaidLifeRules.activity(MaidSchedule.DAY, 12000));
        assertEquals(MaidLifeRules.Activity.WORK, MaidLifeRules.activity(MaidSchedule.DAY, 11999));
        assertEquals(MaidLifeRules.Activity.IDLE, MaidLifeRules.activity(MaidSchedule.NIGHT, 8000));
        assertEquals(MaidLifeRules.Activity.WORK, MaidLifeRules.activity(MaidSchedule.NIGHT, 12000));
    }

    @Test
    public void nativeCakeHasSixBitesAndFinalBiteRemovesTheBlock() {
        int bites = 0, portions = 0;
        while (bites >= 0) {
            portions++;
            bites = MaidLifeRules.nextCakeBites(bites);
        }
        assertEquals(6, portions);
    }

    @Test(expected = IllegalArgumentException.class)
    public void highVersionSeventhBiteIsNotSilentlyWrittenIntoNativeCake() {
        MaidLifeRules.nextCakeBites(6);
    }
}
