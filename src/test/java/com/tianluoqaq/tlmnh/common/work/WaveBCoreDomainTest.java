package com.tianluoqaq.tlmnh.common.work;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.work.bell.BellNbtKeys;
import com.tianluoqaq.tlmnh.common.work.bell.BreedingBellData;
import com.tianluoqaq.tlmnh.common.work.bell.MaintenanceBellData;
import com.tianluoqaq.tlmnh.common.work.bell.MaintenanceBellData.ToggleOutcome;
import com.tianluoqaq.tlmnh.common.work.breeding.BreedingPlotPlanner;
import com.tianluoqaq.tlmnh.common.work.breeding.BreedingPlotPlanner.CellAssignment;
import com.tianluoqaq.tlmnh.common.work.breeding.BreedingPlotPlanner.CellRole;
import com.tianluoqaq.tlmnh.common.work.breeding.config.BreedingTaskConfigData;
import com.tianluoqaq.tlmnh.common.work.breeding.config.BreedingTaskConfigData.StatLogic;
import com.tianluoqaq.tlmnh.common.work.breeding.config.BreedingTaskConfigData.TargetMode;
import com.tianluoqaq.tlmnh.common.work.notify.MaidMissingConditionNotifier;
import com.tianluoqaq.tlmnh.common.work.notify.MissingConditionCode;
import com.tianluoqaq.tlmnh.common.work.notify.MissingConditionEntry;
import com.tianluoqaq.tlmnh.common.work.notify.MissingConditionIds;

public class WaveBCoreDomainTest {

    @Test
    public void maintenanceBellAllowsNonContiguousWithinSpanAndEnforces16ChunkCap() {
        NBTTagCompound tag = new NBTTagCompound();
        assertEquals(ToggleOutcome.BOUND, MaintenanceBellData.toggleChunkOnRoot(tag, 0, 10, 20).outcome);
        assertEquals(ToggleOutcome.BOUND, MaintenanceBellData.toggleChunkOnRoot(tag, 0, 18, 28).outcome);
        assertEquals(ToggleOutcome.REJECTED_SPAN_EXCEEDED, MaintenanceBellData.toggleChunkOnRoot(tag, 0, 19, 20).outcome);
        assertEquals(ToggleOutcome.REJECTED_WRONG_DIMENSION, MaintenanceBellData.toggleChunkOnRoot(tag, -1, 12, 22).outcome);

        // 填满到 16 个区块（全在 [10..18] x [20..28] 窗口内）
        int count = 2;
        for (int cx = 10; cx <= 18 && count < 16; cx++) {
            for (int cz = 20; cz <= 28 && count < 16; cz++) {
                if ((cx == 10 && cz == 20) || (cx == 18 && cz == 28)) continue;
                assertEquals(ToggleOutcome.BOUND, MaintenanceBellData.toggleChunkOnRoot(tag, 0, cx, cz).outcome);
                count++;
            }
        }
        assertEquals(16, MaintenanceBellData.readFromRoot(tag).chunkCount());
        assertEquals(ToggleOutcome.REJECTED_CHUNK_LIMIT, MaintenanceBellData.toggleChunkOnRoot(tag, 0, 15, 25).outcome);

        // 逐个解绑直到最后一块自动移除根标签
        List<int[]> snapshot = MaintenanceBellData.readFromRoot(tag).chunks();
        for (int i = 0; i < snapshot.size() - 1; i++) {
            int[] pair = snapshot.get(i);
            assertEquals(ToggleOutcome.UNBOUND, MaintenanceBellData.toggleChunkOnRoot(tag, 0, pair[0], pair[1]).outcome);
        }
        int[] last = snapshot.get(snapshot.size() - 1);
        assertEquals(ToggleOutcome.CLEARED_ALL, MaintenanceBellData.toggleChunkOnRoot(tag, 0, last[0], last[1]).outcome);
        assertFalse(tag.hasKey(BellNbtKeys.ROOT_MAINTENANCE));
    }

    @Test
    public void breedingBellDataRoundTripsAndClearsCleanly() {
        NBTTagCompound tag = new NBTTagCompound();
        assertFalse(BreedingBellData.readFromRoot(tag).isBound());
        BreedingBellData.bindOnRoot(tag, 0, 100, 64, -200);
        BreedingBellData bound = BreedingBellData.readFromRoot(tag);
        assertTrue(bound.isBound());
        assertEquals(0, bound.dimension());
        assertEquals(100, bound.centerX());
        assertEquals(64, bound.soilY());
        assertEquals(65, bound.cropY());
        assertEquals(63, bound.subSoilY());
        assertEquals(-200, bound.centerZ());
        assertTrue(bound.matches(0, 100, 64, -200));

        BreedingBellData.clearOnRoot(tag);
        assertFalse(BreedingBellData.readFromRoot(tag).isBound());
    }

    @Test
    public void missingConditionNotifierThrottlesRepeatsAndImmediatelyFiresOnChange() {
        MaidMissingConditionNotifier notifier = new MaidMissingConditionNotifier();
        int[] sentCount = new int[1];
        UUID owner = UUID.randomUUID();
        MaidMissingConditionNotifier.OwnerChatSink sink = (u, name, mode, list) -> {
            sentCount[0]++;
            return true;
        };

        List<MissingConditionEntry> bothMissing = Arrays.asList(
            MissingConditionEntry.of(MissingConditionCode.MISSING_MAINTENANCE_BELL),
            MissingConditionEntry.of(MissingConditionCode.MISSING_GT_WRENCH_OFFHAND));

        assertTrue(
            notifier.evaluateAndNotify(
                100L,
                owner,
                "Sakuya",
                MaidTask.MAINTENANCE_HATCH,
                MissingConditionIds.MODE_MAINTENANCE,
                bothMissing,
                sink));
        assertEquals(1, sentCount[0]);

        // 冷却期内相同签名不刷屏
        assertFalse(
            notifier.evaluateAndNotify(
                500L,
                owner,
                "Sakuya",
                MaidTask.MAINTENANCE_HATCH,
                MissingConditionIds.MODE_MAINTENANCE,
                bothMissing,
                sink));
        assertEquals(1, sentCount[0]);

        // 玩家补上了铃铛但仍缺扳手 -> 签名变化立即播报！
        List<MissingConditionEntry> wrenchOnly = Collections
            .singletonList(MissingConditionEntry.of(MissingConditionCode.MISSING_GT_WRENCH_OFFHAND));
        assertTrue(
            notifier.evaluateAndNotify(
                520L,
                owner,
                "Sakuya",
                MaidTask.MAINTENANCE_HATCH,
                MissingConditionIds.MODE_MAINTENANCE,
                wrenchOnly,
                sink));
        assertEquals(2, sentCount[0]);

        // 满 1200 tick 后再次提醒
        assertTrue(
            notifier.evaluateAndNotify(
                1720L,
                owner,
                "Sakuya",
                MaidTask.MAINTENANCE_HATCH,
                MissingConditionIds.MODE_MAINTENANCE,
                wrenchOnly,
                sink));
        assertEquals(3, sentCount[0]);
    }

    @Test
    public void breedingConfigAndPlotPlannerCoverAndOrMax31AndTopologies() {
        BreedingTaskConfigData cfg = new BreedingTaskConfigData();
        cfg.setTargetMode(TargetMode.CROP);
        cfg.setTargetId("cropsnh:carrot");
        cfg.setTargetGrowth(20);
        cfg.setTargetGain(25);
        cfg.setTargetResistance(10);
        cfg.setStatLogic(StatLogic.AND);

        assertFalse(cfg.meetsTargetStats(20, 24, 15));
        assertTrue(cfg.meetsTargetStats(20, 25, 10));

        cfg.setStatLogic(StatLogic.OR);
        assertTrue(cfg.meetsTargetStats(1, 25, 1));
        assertFalse(cfg.meetsTargetStats(19, 24, 9));

        cfg.setMax31Mode(true);
        assertFalse(cfg.meetsTargetStats(31, 31, 30));
        assertTrue(cfg.meetsTargetStats(31, 31, 31));

        NBTTagCompound entityTag = new NBTTagCompound();
        cfg.writeToEntityNbt(entityTag);
        BreedingTaskConfigData restored = new BreedingTaskConfigData();
        restored.readFromEntityNbt(entityTag);
        assertEquals(cfg, restored);

        // 拓扑 A（双亲杂交 -> 13 根作物架）与 拓扑 B（四亲杂交 -> 6 根作物架）
        List<CellAssignment> topoA = BreedingPlotPlanner
            .planCells(false, "cropsnh:sugarBeet", Arrays.asList("cropsnh:carrot", "cropsnh:wheat"));
        assertEquals(13, BreedingPlotPlanner.requiredTotalSticks(topoA));

        List<CellAssignment> topoB = BreedingPlotPlanner
            .planCells(false, "cropsnh:quad", Arrays.asList("a", "b", "c", "d"));
        assertEquals(6, BreedingPlotPlanner.requiredTotalSticks(topoB));
        long crossCountB = topoB.stream().filter(c -> c.role == CellRole.CROSS_DOUBLE).count();
        assertEquals(1L, crossCountB);
    }

    @Test
    public void clearOnRootPreservesCustomDisplayAndOtherNbtTags() {
        NBTTagCompound maintTag = new NBTTagCompound();
        NBTTagCompound display = new NBTTagCompound();
        display.setString("Name", "My Maintenance Bell");
        maintTag.setTag("display", display.copy());
        MaintenanceBellData.toggleChunkOnRoot(maintTag, 0, 3, 4);
        assertTrue(MaintenanceBellData.readFromRoot(maintTag).isBound());
        maintTag.removeTag(BellNbtKeys.ROOT_MAINTENANCE);
        assertFalse(MaintenanceBellData.readFromRoot(maintTag).isBound());
        assertEquals("My Maintenance Bell", maintTag.getCompoundTag("display").getString("Name"));

        NBTTagCompound breedTag = new NBTTagCompound();
        breedTag.setTag("display", display.copy());
        BreedingBellData.bindOnRoot(breedTag, 0, 10, 64, 20);
        assertTrue(BreedingBellData.readFromRoot(breedTag).isBound());
        BreedingBellData.clearOnRoot(breedTag);
        assertFalse(BreedingBellData.readFromRoot(breedTag).isBound());
        assertEquals("My Maintenance Bell", breedTag.getCompoundTag("display").getString("Name"));
    }
}
