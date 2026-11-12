package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;
import com.tianluoqaq.tlmnh.common.work.bell.MaintenanceBellData;
import com.tianluoqaq.tlmnh.common.work.coordinator.MaidWorkItems;
import com.tianluoqaq.tlmnh.common.work.maintenance.MaintenanceHatchWorkController;
import com.tianluoqaq.tlmnh.common.work.notify.MissingConditionCode;

import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchMaintenance;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa", "gregtech" })
public final class MaintenanceHatchWorkNativeTests {

    private MaintenanceHatchWorkNativeTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 900, batch = "work_maintenance")
    public static void ordinaryHatchRepairsSixActualControllerFaultsThenPatrolsSeparatedChunksWithoutConsumption(
        GameTestHelper h) throws Exception {
        var f = new LifecycleConservationTests.Fixture(h);
        var world = h.getWorld();
        var chunks = new GameTestChunkLoader();
        h.afterTest(chunks::releaseAll);
        chunks.forceChunksStrict(world, f.x - 80, f.y, f.z - 80, f.x + 100, f.y + 10, f.z + 90);
        long originalTime = world.getWorldTime();
        boolean originalDisable = MTEMultiBlockBase.disableMaintenance;
        world.setWorldTime(6000);
        MTEMultiBlockBase.disableMaintenance = false;
        h.afterTest(() -> {
            world.setWorldTime(originalTime);
            MTEMultiBlockBase.disableMaintenance = originalDisable;
        });
        world.playerEntities.add(f.owner);
        h.afterTest(() -> { world.playerEntities.removeIf(player -> player == f.owner); });
        int floorX = (f.x >> 4) << 4, floorZ = (f.z >> 4) << 4;
        // 本例使用独立 selector。平台跨越三个区块，绑定首尾两个区块，中间一块只提供安全通路
        for (int dx = 0; dx < 48; dx++)
            for (int dz = 0; dz < 16; dz++) world.setBlock(floorX + dx, f.y, floorZ + dz, Blocks.stone);
        int hx = floorX + 10, hz = floorZ + 8, hy = f.y + 1;
        var hatchBase = place(h, ItemList.Hatch_Maintenance.get(1L), hx, hy, hz);
        var autoBase = place(h, ItemList.Hatch_AutoMaintenance.get(1L), hx + 4, hy, hz);
        var controllerBase = place(h, ItemList.Machine_Multi_BlastFurnace.get(1L), hx + 2, hy, hz);
        var hatch = (MTEHatchMaintenance) hatchBase.getMetaTileEntity();
        var controller = (MTEMultiBlockBase) controllerBase.getMetaTileEntity();
        controller.mMachine = true;
        controller.mStartUpCheck = -1;
        controller.mUpdate = 100000;
        controller.mUpdated = false;
        controller.mMaintenanceHatches.clear();
        controller.mMaintenanceHatches.add(hatch);
        hatch.mWrench = hatch.mScrewdriver = hatch.mSoftMallet = hatch.mHardHammer = hatch.mSolderingTool = hatch.mCrowbar = false;
        controller.mWrench = controller.mScrewdriver = controller.mSoftMallet = controller.mHardHammer = controller.mSolderingTool = controller.mCrowbar = false;
        h.assertTrue(
            controller.getRepairStatus() == 0 && controller.getIdealStatus() == 6,
            "Actual controller did not begin with six maintenance faults");
        var provider = CompatServices.maintenanceHatch();
        h.assertTrue(
            provider.isAvailable() && provider.isOrdinaryMaintenanceHatch(world, hx, hy, hz),
            "Ordinary maintenance hatch was not recognized");
        h.assertTrue(
            !provider.isOrdinaryMaintenanceHatch(world, hx + 4, hy, hz)
                && !provider.performZeroSideEffectMaintenance(world, hx + 4, hy, hz),
            "Automatic maintenance hatch entered ordinary maintenance work");
        var maid = new EntityMaid(world);
        f.owned.add(maid);
        h.assertTrue(maid.bindTo(f.owner.getUniqueID()), "Work maid binding failed");
        maid.setCustomNameTag("[QA] Maintenance Worker");
        maid.setSitting(false);
        maid.setPickup(false);
        maid.setSchedule(MaidSchedule.DAY);
        maid.setFavorability(0);
        h.spawnEntity(maid, floorX + 4.5 - f.x, 1, floorZ + 8.5 - f.z);
        var bell = new ItemStack(MaidWorkItems.maintenanceBell);
        assertMissing(h, maid, MissingConditionCode.MISSING_MAINTENANCE_BELL);
        assertMissing(h, maid, MissingConditionCode.MISSING_GT_WRENCH_OFFHAND);
        maid.getMaidBaubleInventory()
            .setInventorySlotContents(0, bell);
        assertMissing(h, maid, MissingConditionCode.UNBOUND_MAINTENANCE_BELL);
        MaintenanceBellData.toggleChunk(bell, world.provider.dimensionId + 1, floorX >> 4, floorZ >> 4);
        assertMissing(h, maid, MissingConditionCode.WRONG_DIMENSION_MAINTENANCE_BELL);
        MaintenanceBellData.clear(bell);
        MaintenanceBellData.toggleChunk(bell, world.provider.dimensionId, floorX >> 4, floorZ >> 4);
        MaintenanceBellData.toggleChunk(bell, world.provider.dimensionId, (floorX >> 4) + 2, floorZ >> 4);
        maid.getMaidInventory()
            .setInventorySlotContents(MaidInventoryLayout.OFF_HAND, new ItemStack(Items.iron_pickaxe));
        assertMissing(h, maid, MissingConditionCode.MISSING_GT_WRENCH_OFFHAND);
        var wrench = wrench();
        var tape = ItemList.Duct_Tape.get(9L);
        h.assertTrue(
            provider.isValidMaintenanceWrench(wrench) && tape != null,
            "Actual GT wrench and duct tape required");
        maid.getMaidInventory()
            .setInventorySlotContents(MaidInventoryLayout.OFF_HAND, wrench);
        maid.getMaidInventory()
            .setInventorySlotContents(0, tape);
        h.assertTrue(
            MaintenanceHatchWorkController.collectMissingConditions(maid, provider)
                .isEmpty(),
            "Complete work equipment was rejected");
        var wrenchBefore = wrench.copy();
        var tapeBefore = tape.copy();
        var bellBefore = bell.copy();
        var hatchFacing = hatchBase.getFrontFacing();
        var autoFacing = autoBase.getFrontFacing();
        var controllerFacing = controllerBase.getFrontFacing();
        var found = provider.findNeedingMaintenanceHatches(
            world,
            MaintenanceBellData.read(bell)
                .chunks());
        h.assertTrue(
            found.size() == 1 && found.get(0).x == hx && found.get(0).y == hy && found.get(0).z == hz,
            "Bound-domain scan did not select only the broken ordinary hatch");
        maid.setTask(MaidTask.MAINTENANCE_HATCH);
        maid.getBrainCoordinator()
            .setSuspended(false);
        int[] repairedAt = { -1 };
        boolean[] swing = { false };
        boolean[] visitedSecondChunk = { false };
        var positions = new ArrayList<Double>();
        h.onEachTick("normal maid maintenance work and healthy domain patrol", () -> {
            if (maid.ticksExisted % 100 == 0) System.out.println(
                "TLMNH_MAINTENANCE_WORK_STATE age=" + maid.ticksExisted
                    + " x="
                    + maid.posX
                    + " y="
                    + maid.posY
                    + " z="
                    + maid.posZ
                    + " repair="
                    + controller.getRepairStatus()
                    + " repairedAt="
                    + repairedAt[0]
                    + " path="
                    + (maid.getNavigator()
                        .getPath() != null)
                    + " task="
                    + maid.getTask()
                    + " work="
                    + maid.getBrainCoordinator()
                        .getWorkModeCoordinator()
                        .isInWorkSchedule(maid)
                    + " profile="
                    + maid.getBrainCoordinator()
                        .getPolicyRuntime()
                        .getProfile()
                    + " intent="
                    + maid.getBrainCoordinator()
                        .getOrdinaryIntent()
                    + " ownerBound="
                    + maid.isOwner(f.owner));
            h.assertTrue(
                maid.isEntityAlive() && !maid.getBrainCoordinator()
                    .isSuspended(),
                "Normal work maid became inactive");
            h.assertTrue(
                ItemStack.areItemStacksEqual(wrenchBefore, maid.getOffhandItem())
                    && ItemStack.areItemStacksEqual(
                        tapeBefore,
                        maid.getMaidInventory()
                            .getStackInSlot(0))
                    && ItemStack.areItemStacksEqual(
                        bellBefore,
                        maid.getMaidBaubleInventory()
                            .getStackInSlot(0)),
                "Maintenance consumed wrench durability, energy, duct tape or bell data");
            h.assertTrue(
                hatchFacing == hatchBase.getFrontFacing() && autoFacing == autoBase.getFrontFacing()
                    && controllerFacing == controllerBase.getFrontFacing(),
                "Maintenance rotated an actual GT machine");
            if (maid.isSwingInProgress) swing[0] = true;
            if (controller.getRepairStatus() == 6 && repairedAt[0] < 0) {
                repairedAt[0] = maid.ticksExisted;
                h.assertTrue(
                    provider.findNeedingMaintenanceHatches(
                        world,
                        MaintenanceBellData.read(bell)
                            .chunks())
                        .isEmpty(),
                    "Healthy controller remained in the repair queue");
            }
            if (repairedAt[0] >= 0 && maid.ticksExisted % 20 == 0) positions.add(maid.posX);
        });
        h.succeedWhen(() -> {
            if (repairedAt[0] < 0) return false;
            if (maid.posX >= floorX + 38) visitedSecondChunk[0] = true;
            if (!visitedSecondChunk[0] || maid.posX > floorX + 11) return false;
            h.assertTrue(
                swing[0] && controller.getRepairStatus() == controller.getIdealStatus(),
                "Maintenance lacked its visual swing or lost a repaired fault");
            System.out.println(
                "TLMNH_MAINTENANCE_WORK_PASS repair=0->6 mode=" + maid.getTask()
                    + " repairedAt="
                    + repairedAt[0]
                    + " age="
                    + maid.ticksExisted
                    + " zeroItemChanges=true zeroRotation=true ordinary=90 excludedAuto=111 roundTrip=true patrolX="
                    + positions);
            return true;
        });
    }

    private static IGregTechTileEntity place(GameTestHelper h, ItemStack stack, int x, int y, int z) {
        h.assertTrue(stack != null && stack.getItem() instanceof ItemBlock, "Registered GT machine item required");
        var item = (ItemBlock) stack.getItem();
        h.assertTrue(
            item.placeBlockAt(stack.copy(), null, h.getWorld(), x, y, z, 1, 0.5F, 0.5F, 0.5F, stack.getItemDamage()),
            "Legal GT machine placement failed");
        var tile = h.getWorld()
            .getTileEntity(x, y, z);
        h.assertTrue(tile instanceof IGregTechTileEntity, "GT placement did not initialize its actual base tile");
        return (IGregTechTileEntity) tile;
    }

    private static void assertMissing(GameTestHelper h, EntityMaid maid, MissingConditionCode code) {
        h.assertTrue(
            MaintenanceHatchWorkController.collectMissingConditions(maid, CompatServices.maintenanceHatch())
                .stream()
                .anyMatch(entry -> entry.code == code),
            "Missing maintenance condition was not detected: " + code);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static ItemStack wrench() {
        try {
            var type = Class.forName("gregtech.common.items.MetaGeneratedTool01");
            var ids = Class.forName("gregtech.common.items.IDMetaTool01");
            var materials = Class.forName("gregtech.api.enums.Materials");
            int id = ids.getField("ID")
                .getInt(Enum.valueOf((Class<Enum>) ids, "WRENCH"));
            var steel = materials.getField("Steel")
                .get(null);
            return (ItemStack) type
                .getMethod("getToolWithStats", int.class, int.class, materials, materials, long[].class)
                .invoke(
                    type.getField("INSTANCE")
                        .get(null),
                    id,
                    1,
                    steel,
                    steel,
                    null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot construct the reviewed actual GT steel wrench", failure);
        }
    }
}
