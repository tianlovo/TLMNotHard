package com.tianluoqaq.tlmnh.common.development.work;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S09PacketHeldItemChange;
import net.minecraft.world.WorldServer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.development.inventory.MaidInventoryUiFixture;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.foundation.ModContext;
import com.tianluoqaq.tlmnh.common.work.bell.BreedingBellData;
import com.tianluoqaq.tlmnh.common.work.bell.MaintenanceBellData;
import com.tianluoqaq.tlmnh.common.work.breeding.config.BreedingTaskConfigData;
import com.tianluoqaq.tlmnh.common.work.coordinator.MaidWorkItems;
import com.tianluoqaq.tlmnh.common.work.maintenance.MaintenanceHatchWorkController;
import com.tianluoqaq.tlmnh.common.work.notify.MaidMissingConditionNotifier;
import com.tianluoqaq.tlmnh.compat.backhand.development.player.BackhandAccess;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** 复用背包夹具的保护和原始库存恢复，独立维护工作配置与四件铃铛输入 */
public final class MaidWorkUiFixture {

    private static final Map<UUID, State> ACTIVE = new HashMap<>();
    private static boolean registered;

    private MaidWorkUiFixture() {}

    public static void run(EntityPlayerMP player, String action) {
        require(
            DevelopmentFlags.isDevCommandsEnabled() && AnimationLab.isLab(player.worldObj),
            "Protected development lab required");
        var state = ACTIVE.get(player.getUniqueID());
        if (action.equals("prepare")) {
            require(state == null, "Fresh work fixture required");
            MaidInventoryUiFixture.run(player, "prepare");
            state = new State(player, AnimationLab.sceneMaid((WorldServer) player.worldObj));
            ACTIVE.put(player.getUniqueID(), state);
            state.prepare();
            if (!registered) {
                FMLCommonHandler.instance()
                    .bus()
                    .register(new MaidWorkUiFixture());
                registered = true;
            }
        } else {
            require(
                state != null && state.player == player
                    && state.maid.worldObj == player.worldObj
                    && state.maid.isEntityAlive()
                    && state.maid.isOwner(player),
                "Same live owned work fixture required");
            switch (action) {
                case "status" -> {}
                case "open" -> MaidInventoryUiFixture.run(player, "open");
                case "close" -> player.closeScreen();
                case "hold-maintenance" -> state.hold(2, false);
                case "hold-breeding" -> state.hold(3, false);
                case "hold-none" -> state.hold(4, false);
                case "offhand-maintenance" -> state.hold(4, true);
                case "rebind" -> state.rebind();
                case "unequip" -> state.unequip();
                case "reequip" -> state.reequip();
                case "fresh-track" -> state.freshTrack();
                case "notify-maintenance" -> new MaintenanceHatchWorkController()
                    .tick(state.maid, CompatServices.maintenanceHatch(), state.notifier);
                case "finish" -> {
                    state.finish();
                    ACTIVE.remove(player.getUniqueID());
                }
                default -> throw new IllegalArgumentException("Unknown work fixture action");
            }
        }
        ModContext.LOG.info("TLMNH_WORK_UI_FIXTURE {}", state.snapshot(action));
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (var state : ACTIVE.values()) {
            // 持续潜行会让飞行玩家下降；固定观察相机在已核实的羊毛平台上方
            if (state.player.worldObj == state.maid.worldObj && state.player.posY < 65) {
                state.player.motionY = 0;
                state.player.fallDistance = 0;
                state.player.playerNetServerHandler.setPlayerLocation(
                    state.player.posX,
                    65,
                    state.player.posZ,
                    state.player.rotationYaw,
                    state.player.rotationPitch);
            }
            if (state.retrackAt >= 0 && state.maid.worldObj.getTotalWorldTime() >= state.retrackAt) {
                require(state.maid.isEntityAlive(), "Tracked fixture maid died before retracking");
                ((WorldServer) state.maid.worldObj).getEntityTracker()
                    .addEntityToTracker(state.maid);
                state.retrackAt = -1;
            }
        }
    }

    private static final class State {

        final EntityPlayerMP player;
        final EntityMaid maid;
        final MaidTask originalTask;
        final BreedingTaskConfigData originalConfig;
        final String originalName;
        final MaidMissingConditionNotifier notifier = new MaidMissingConditionNotifier();
        final ItemStack[] originalBaubles;
        final String marker = UUID.randomUUID()
            .toString();
        final ItemStack heldMaintenance, heldBreeding, wornMaintenance, wornBreeding;
        final double originalX, originalY, originalZ;
        final float originalYaw, originalPitch;
        int offhandSlot = -1;
        long retrackAt = -1;
        boolean restored;

        State(EntityPlayerMP player, EntityMaid maid) {
            this.player = player;
            this.maid = maid;
            originalX = player.posX;
            originalY = player.posY;
            originalZ = player.posZ;
            originalYaw = player.rotationYaw;
            originalPitch = player.rotationPitch;
            require(
                maid != null && maid.isOwner(player)
                    && maid.getBrainCoordinator()
                        .isSuspended(),
                "Suspended owned lab maid required");
            originalTask = maid.getTask();
            originalName = maid.getCustomNameTag();
            originalConfig = maid.getBreedingTaskConfig()
                .copy();
            originalBaubles = new ItemStack[maid.getMaidBaubleInventory()
                .getSizeInventory()];
            for (int slot = 0; slot < originalBaubles.length; slot++) originalBaubles[slot] = ItemStack.copyItemStack(
                maid.getMaidBaubleInventory()
                    .getStackInSlot(slot));
            heldMaintenance = bell(true);
            heldBreeding = bell(false);
            wornMaintenance = bell(true);
            wornBreeding = bell(false);
        }

        ItemStack bell(boolean maintenance) {
            var stack = new ItemStack(maintenance ? MaidWorkItems.maintenanceBell : MaidWorkItems.breedingBell);
            stack.setStackDisplayName("[QA] Work bell " + marker);
            stack.getTagCompound()
                .setString("TLMNHWorkUiFixture", marker);
            int dim = maid.worldObj.provider.dimensionId;
            if (maintenance) {
                MaintenanceBellData.toggleChunk(stack, dim, 0, 0);
                MaintenanceBellData.toggleChunk(stack, dim, 2, 2);
            } else BreedingBellData.bind(stack, dim, 0, 64, 0);
            return stack;
        }

        void prepare() {
            require(
                player.inventory.getStackInSlot(2) == null && player.inventory.getStackInSlot(3) == null
                    && player.inventory.getStackInSlot(4) == null
                    && player.inventory.getStackInSlot(5) == null,
                "Declared fixture slots must be empty");
            maid.setTask(MaidTask.IDLE);
            maid.setCustomNameTag("[QA] Work UI");
            maid.getBreedingTaskConfig()
                .clear();
            for (int slot = 0; slot < originalBaubles.length; slot++) maid.getMaidBaubleInventory()
                .setInventorySlotContents(slot, null);
            maid.getMaidBaubleInventory()
                .setInventorySlotContents(0, wornMaintenance);
            maid.getMaidBaubleInventory()
                .setInventorySlotContents(1, wornBreeding);
            player.inventory.setInventorySlotContents(2, heldMaintenance);
            player.inventory.setInventorySlotContents(3, heldBreeding);
            player.inventoryContainer.detectAndSendChanges();
            player.playerNetServerHandler.setPlayerLocation(0.5, 65, -2, 0, 15);
        }

        void hold(int slot, boolean offhand) {
            player.closeScreen();
            restoreOffhand();
            player.inventory.currentItem = slot;
            player.playerNetServerHandler.sendPacket(new S09PacketHeldItemChange(slot));
            if (offhand) {
                try {
                    int destination = BackhandAccess.offhandSlot(player);
                    require(destination != slot && destination != 2, "Distinct inactive Backhand slot required");
                    var previous = player.inventory.getStackInSlot(destination);
                    player.inventory.setInventorySlotContents(destination, player.inventory.getStackInSlot(2));
                    player.inventory.setInventorySlotContents(2, previous);
                    offhandSlot = destination;
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Actual Backhand slot lookup failed", failure);
                }
            }
            player.inventoryContainer.detectAndSendChanges();
        }

        void restoreOffhand() {
            if (offhandSlot < 0) return;
            require(isFixtureBell(player.inventory.getStackInSlot(offhandSlot)), "Inactive fixture bell changed");
            var previous = player.inventory.getStackInSlot(2);
            player.inventory.setInventorySlotContents(2, player.inventory.getStackInSlot(offhandSlot));
            player.inventory.setInventorySlotContents(offhandSlot, previous);
            offhandSlot = -1;
        }

        void rebind() {
            require(
                maid.getMaidBaubleInventory()
                    .getStackInSlot(0) == wornMaintenance,
                "Worn maintenance bell required");
            MaintenanceBellData.clear(wornMaintenance);
            int dim = maid.worldObj.provider.dimensionId;
            MaintenanceBellData.toggleChunk(wornMaintenance, dim, 1, 0);
            MaintenanceBellData.toggleChunk(wornMaintenance, dim, 3, 2);
            MaintenanceBellData.toggleChunk(wornMaintenance, dim, 4, 4);
            maid.getMaidBaubleInventory()
                .markDirty();
        }

        void unequip() {
            require(
                maid.getMaidBaubleInventory()
                    .getStackInSlot(0) == wornMaintenance && player.inventory.getStackInSlot(5) == null,
                "Exact worn bell and empty destination required");
            maid.getMaidBaubleInventory()
                .setInventorySlotContents(0, null);
            player.inventory.setInventorySlotContents(5, wornMaintenance);
            player.inventoryContainer.detectAndSendChanges();
        }

        void reequip() {
            require(
                maid.getMaidBaubleInventory()
                    .getStackInSlot(0) == null && player.inventory.getStackInSlot(5) == wornMaintenance,
                "Exact removed bell required");
            player.inventory.setInventorySlotContents(5, null);
            maid.getMaidBaubleInventory()
                .setInventorySlotContents(0, wornMaintenance);
            player.inventoryContainer.detectAndSendChanges();
        }

        void freshTrack() {
            require(
                retrackAt < 0 && player.openContainer == player.inventoryContainer,
                "Closed screen and no pending tracking transaction required");
            ((WorldServer) maid.worldObj).getEntityTracker()
                .removeEntityFromAllTrackingPlayers(maid);
            retrackAt = maid.worldObj.getTotalWorldTime() + 5;
        }

        boolean isFixtureBell(ItemStack stack) {
            return stack != null && stack.hasTagCompound()
                && marker.equals(
                    stack.getTagCompound()
                        .getString("TLMNHWorkUiFixture"))
                && (stack.getItem() == MaidWorkItems.maintenanceBell || stack.getItem() == MaidWorkItems.breedingBell);
        }

        void finish() {
            require(
                retrackAt < 0 && player.inventory.getItemStack() == null,
                "Finish pending native transactions first");
            restoreOffhand();
            int maintenance = 0, breeding = 0;
            for (var inventory : new net.minecraft.inventory.IInventory[] { player.inventory,
                maid.getMaidBaubleInventory() }) for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
                    var stack = inventory.getStackInSlot(slot);
                    if (!isFixtureBell(stack)) continue;
                    require(stack.stackSize == 1, "Fixture bell stack count changed");
                    if (stack.getItem() == MaidWorkItems.maintenanceBell) maintenance++;
                    else breeding++;
                }
            require(maintenance == 2 && breeding == 2, "Exactly four generated bell instances required for cleanup");
            for (var inventory : new net.minecraft.inventory.IInventory[] { player.inventory,
                maid.getMaidBaubleInventory() })
                for (int slot = 0; slot < inventory.getSizeInventory(); slot++)
                    if (isFixtureBell(inventory.getStackInSlot(slot))) inventory.setInventorySlotContents(slot, null);
            for (int slot = 0; slot < originalBaubles.length; slot++) {
                require(
                    maid.getMaidBaubleInventory()
                        .getStackInSlot(slot) == null,
                    "Unknown bauble successor retained");
                maid.getMaidBaubleInventory()
                    .setInventorySlotContents(slot, ItemStack.copyItemStack(originalBaubles[slot]));
            }
            maid.setTask(originalTask);
            maid.setCustomNameTag(originalName);
            maid.getBreedingTaskConfig()
                .copyFrom(originalConfig);
            MaidInventoryUiFixture.run(player, "finish");
            player.playerNetServerHandler
                .setPlayerLocation(originalX, originalY, originalZ, originalYaw, originalPitch);
            restored = true;
        }

        JsonObject snapshot(String action) {
            var result = new JsonObject();
            result.addProperty("action", action);
            result.addProperty(
                "maidUUID",
                maid.getUniqueID()
                    .toString());
            result.addProperty("maidEntityId", maid.getEntityId());
            result.addProperty(
                "task",
                maid.getTask()
                    .name());
            result.addProperty("windowId", player.openContainer.windowId);
            result.addProperty(
                "containerClass",
                player.openContainer.getClass()
                    .getName());
            result.addProperty("offhandSlot", offhandSlot);
            result.addProperty("playerSneaking", player.isSneaking());
            result.addProperty("playerY", player.posY);
            result.addProperty("distanceSquared", player.getDistanceSqToEntity(maid));
            result.addProperty("retrackPending", retrackAt >= 0);
            result.addProperty("originalInputsRestored", restored);
            var config = maid.getBreedingTaskConfig();
            var value = new JsonObject();
            value.addProperty(
                "mode",
                config.targetMode()
                    .name());
            value.addProperty("target", config.targetId());
            value.addProperty("gain", config.targetGain());
            value.addProperty("growth", config.targetGrowth());
            value.addProperty("resistance", config.targetResistance());
            value.addProperty(
                "logic",
                config.statLogic()
                    .name());
            value.addProperty("max31", config.max31Mode());
            result.add("config", value);
            return result;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
