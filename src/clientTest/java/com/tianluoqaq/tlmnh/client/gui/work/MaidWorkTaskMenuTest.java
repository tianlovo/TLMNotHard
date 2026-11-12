package com.tianluoqaq.tlmnh.client.gui.work;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.container.MaidContainerProtocol;

public class MaidWorkTaskMenuTest {

    @Test
    public void compactTaskRowsKeepTheirDeclaredActionsAndPreserveScheduleActions() {
        assertEquals(
            4,
            MaidWorkTaskMenu.tasks(true)
                .size());
        assertEquals(
            5,
            MaidWorkTaskMenu.tasks(false)
                .size());
        assertFalse(
            MaidWorkTaskMenu.tasks(true)
                .contains(MaidTask.RANGED_ATTACK));
        var actions = new HashSet<Integer>();
        for (var task : MaidWorkTaskMenu.tasks(false)) {
            int action = MaidWorkTaskMenu.action(task);
            assertTrue(actions.add(action));
            assertEquals(task, MaidWorkTaskMenu.task(action));
        }
        assertNull(MaidWorkTaskMenu.task(MaidContainerProtocol.ACTION_DAY));
        assertNull(MaidWorkTaskMenu.task(MaidContainerProtocol.ACTION_NIGHT));
        assertEquals(
            MaidContainerProtocol.ACTION_MAINTENANCE_HATCH,
            MaidWorkTaskMenu.action(MaidTask.MAINTENANCE_HATCH));
        assertEquals(MaidContainerProtocol.ACTION_CROPSNH_BREEDING, MaidWorkTaskMenu.action(MaidTask.CROPSNH_BREEDING));
    }
}
