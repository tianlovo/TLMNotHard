package com.tianluoqaq.tlmnh.qa;

import java.util.HashSet;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.IWorldAccess;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.life.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.ProtectionEvents;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 实际IWorldAccess故障回调与Forge放置取消；不代表外部模组任意覆写或磁盘崩溃原子性 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class MaidBedPlacementTests {

    private static final String PENDING = "TLMNHBedPlacementPending";

    private MaidBedPlacementTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "bed_place_foreign")
    public static void actualHeadCallbackForeignTileKeepsEnchantedAppleAndOriginalSource(GameTestHelper helper) {
        var f = new Fixture(helper, "foreign_tile");
        helper.assertFalse(f.place(false), "Foreign head tile was accepted as our bed");
        helper.assertTrue(
            f.gate.calls == 1 && f.worldTile() == f.gate.chest,
            "Failed placement deleted or replaced the foreign tile");
        helper.assertTrue(
            ItemStack.areItemStacksEqual(f.apple, f.gate.chest.getStackInSlot(0)),
            "Foreign tile lost its enchanted apple/custom NBT");
        f.sourceUnchanged();
        f.oneWarningAndNoRetry();
        helper.succeedWhen(() -> {
            f.noDuplicateValue();
            helper.assertTrue(
                f.worldTile() == f.gate.chest && ItemStack.areItemStacksEqual(f.apple, f.gate.chest.getStackInSlot(0)),
                "Foreign data changed later");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "bed_place_throw")
    public static void actualLinkageFailureRetainsFullSourceMarkerAndRejectsUseAfterNbtReload(GameTestHelper helper) {
        var f = new Fixture(helper, "linkage");
        helper.assertFalse(f.place(false), "Linkage failure escaped or committed placement");
        f.sourceUnchanged();
        helper.assertTrue(
            f.gate.calls == 1 && f.worldTile() instanceof TileEntityMaidBed,
            "Failure did not retain the provisional head");
        var first = new NBTTagCompound();
        ((TileEntityMaidBed) f.worldTile()).writeToNBT(first);
        var marker = first.getCompoundTag(PENDING);
        helper.assertTrue(
            marker.getCompoundTag("Source")
                .equals(f.original.writeToNBT(new NBTTagCompound())),
            "Pending marker lost full original item data");
        // 原生NBT重建保留已知marker，不从中自动生成可领取物品
        var reloaded = new TileEntityMaidBed();
        reloaded.readFromNBT(first);
        helper.getWorld()
            .setTileEntity(f.x, f.y, f.z, reloaded);
        reloaded.updateEntity();
        f.retainedBedCannotExport(reloaded);
        var again = new NBTTagCompound();
        reloaded.writeToNBT(again);
        helper.assertTrue(marker.equals(again.getTag(PENDING)), "Reload rewrote the original pending marker");
        f.oneWarningAndNoRetry();
        helper.succeedWhen(() -> {
            f.noDuplicateValue();
            helper.assertTrue(f.worldTile() == reloaded, "Pending head was automatically deleted");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "bed_place_source")
    public static void actualCallbackHandReplacementKeepsSuccessorAndDoesNotConsumeOrRestoreSource(
        GameTestHelper helper) {
        var f = new Fixture(helper, "source");
        helper.assertFalse(f.place(false), "Changed source hand was committed");
        helper.assertTrue(
            f.gate.calls == 1 && f.owner.getCurrentEquippedItem() == f.gate.successor
                && ItemStack.areItemStacksEqual(f.apple, f.gate.successor),
            "Fallback overwrote the successor hand");
        helper.assertTrue(ItemStack.areItemStacksEqual(f.source, f.original), "Detached source count/NBT changed");
        var bed = (TileEntityMaidBed) f.worldTile();
        f.retainedBedCannotExport(bed);
        var saved = new NBTTagCompound();
        bed.writeToNBT(saved);
        helper.assertTrue(
            saved.getCompoundTag(PENDING)
                .getCompoundTag("Source")
                .equals(f.original.writeToNBT(new NBTTagCompound())),
            "Detached original record was lost");
        // 此例保留successor，因此不调用会主动换回原栈的place重试夹具
        f.oneWarning();
        helper.succeedWhen(() -> {
            f.noDuplicateValue();
            helper.assertTrue(f.owner.getCurrentEquippedItem() == f.gate.successor, "Later tick replaced successor");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "bed_place_forge")
    public static void actualForgeMultiPlaceCancellationRestoresBothAirCellsAndOriginalItem(GameTestHelper helper) {
        var f = new Fixture(helper, "none");
        var cancel = new Cancel(f);
        MinecraftForge.EVENT_BUS.register(cancel);
        helper.afterTest(() -> MinecraftForge.EVENT_BUS.unregister(cancel));
        helper.assertFalse(f.place(true), "Actual Forge cancellation was ignored");
        f.sourceUnchanged();
        helper.assertTrue(
            cancel.calls == 1 && cancel.completeAtEvent,
            "Forge event did not see a complete committed bed");
        helper.assertTrue(
            helper.getWorld()
                .getBlock(f.x, f.y, f.z) == Blocks.air
                && helper.getWorld()
                    .getBlock(f.x, f.y, f.z + 1) == Blocks.air
                && f.worldTile() == null
                && !helper.getWorld().captureBlockSnapshots
                && !helper.getWorld().restoringBlockSnapshots
                && helper.getWorld().capturedBlockSnapshots.isEmpty(),
            "Forge cancellation failed to restore cells/capture state");
        helper.succeedWhen(() -> {
            f.noDuplicateValue();
            return true;
        });
    }

    public static final class Cancel {

        final Fixture f;
        int calls;
        boolean completeAtEvent;

        Cancel(Fixture f) {
            this.f = f;
        }

        @SubscribeEvent
        public void place(BlockEvent.MultiPlaceEvent event) {
            if (event.player != f.owner) return;
            calls++;
            completeAtEvent = f.worldTile() instanceof TileEntityMaidBed bed && bed.complete();
            event.setCanceled(true);
        }
    }

    private static final class Fixture {

        final GameTestHelper helper;
        final ModernCoreTests.Fixture base;
        final EntityPlayerMP owner;
        final ItemStack source, original, apple;
        final java.util.Set<java.util.UUID> initialPending;
        final Gate gate;
        final int x, y, z;

        Fixture(GameTestHelper helper, String fault) {
            this.helper = helper;
            base = new ModernCoreTests.Fixture(helper, false);
            owner = new EntityPlayerMP(
                MinecraftServer.getServer(),
                helper.getWorld(),
                new GameProfile(java.util.UUID.randomUUID(), "QA-BedPlace"),
                new ItemInWorldManager(helper.getWorld()));
            var manager = new NetworkManager(false);
            var connection = new EmbeddedChannel(manager);
            manager.setConnectionState(EnumConnectionState.PLAY);
            new NetHandlerPlayServer(MinecraftServer.getServer(), manager, owner);
            IsolatedPlayerChannel.verify(manager, connection);
            helper.afterTest(() -> {
                owner.setDead();
                connection.finish();
                Object message;
                while ((message = connection.readOutbound()) != null) ReferenceCountUtil.release(message);
                while ((message = connection.readInbound()) != null) ReferenceCountUtil.release(message);
            });
            owner.setPosition(base.x + 8.5, base.y + 1, base.z + 6.5);

            owner.theItemInWorldManager.setGameType(net.minecraft.world.WorldSettings.GameType.SURVIVAL);
            base.maid.getBrainCoordinator()
                .setSuspended(true);
            x = base.x + 8;
            y = base.y + 1;
            z = base.z + 4;
            helper.setBlock(8, 0, 4, Blocks.stone);
            helper.setBlock(8, 0, 5, Blocks.stone);
            initialPending = new HashSet<>(
                MaidLifecycleData.get(helper.getWorld())
                    .pendingReturnedFilms(helper.getWorld().provider.dimensionId));
            source = new ItemStack(MaidLifeContent.maidBedItem);
            var tag = new NBTTagCompound();
            var rawColor = new NBTTagCompound();
            rawColor.setInteger("Revision", 43);
            rawColor.setString("Unknown", "preserve_raw_color");
            tag.setTag("BedColor", rawColor);
            tag.setString("Extra", "original_source_metadata");
            source.setTagCompound(tag);
            original = source.copy();
            apple = new ItemStack(Items.golden_apple, 1, 1);
            var appleTag = new NBTTagCompound();
            appleTag.setString("Custom", "foreign_successor");
            apple.setTagCompound(appleTag);
            gate = new Gate(this, fault);
            helper.getWorld()
                .addWorldAccess(gate);
            helper.afterTest(() -> {
                helper.getWorld()
                    .removeWorldAccess(gate);
                helper.getWorld()
                    .setBlock(x, y, z, Blocks.air, 0, 2);
                helper.getWorld()
                    .setBlock(x, y, z + 1, Blocks.air, 0, 2);
            });
        }

        net.minecraft.tileentity.TileEntity worldTile() {
            return helper.getWorld()
                .getTileEntity(x, y, z);
        }

        boolean place(boolean forge) {
            owner.inventory.mainInventory[owner.inventory.currentItem] = source;
            owner.rotationYaw = 180;
            return forge
                ? ForgeHooks.onPlaceItemIntoWorld(source, owner, helper.getWorld(), x, y - 1, z + 1, 1, .5F, .5F, .5F)
                : MaidLifeContent.maidBedItem
                    .onItemUse(source, owner, helper.getWorld(), x, y - 1, z + 1, 1, .5F, .5F, .5F);
        }

        void sourceUnchanged() {
            helper.assertTrue(
                owner.getCurrentEquippedItem() == source && ItemStack.areItemStacksEqual(source, original),
                "Failed/cancelled placement lost or rewrote original source");
        }

        void retainedBedCannotExport(TileEntityMaidBed bed) {
            helper.assertFalse(bed.complete(), "Pending bed became usable");
            for (int offset : new int[] { 0, 1 }) {
                helper.assertTrue(
                    MaidLifeContent.maidBed.getPickBlock(null, helper.getWorld(), x, y, z + offset, owner) == null,
                    "Pending bed clone exported value");
                helper.assertFalse(
                    owner.theItemInWorldManager.tryHarvestBlock(x, y, z + offset),
                    "Pending bed could be harvested");
            }
            var dye = new ItemStack(Items.dye, 2, 15);
            var held = owner.getCurrentEquippedItem();
            owner.inventory.mainInventory[owner.inventory.currentItem] = dye;
            helper.assertFalse(
                MaidLifeContent.maidBed.onBlockActivated(helper.getWorld(), x, y, z, owner, 1, .5F, .5F, .5F),
                "Pending bed accepted dye");
            helper.assertTrue(dye.stackSize == 2, "Pending bed consumed dye");
            owner.inventory.mainInventory[owner.inventory.currentItem] = held;
            MaidLifeContent.maidBed.dropBlockAsItem(helper.getWorld(), x, y, z, 10, 0);
            helper.assertTrue(
                MaidLifeContent.maidBed.getDrops(helper.getWorld(), x, y, z, 10, 0)
                    .isEmpty(),
                "Pending bed returned drops");
        }

        void oneWarningAndNoRetry() {
            int before = gate.calls;
            helper.assertFalse(place(false), "Failed player placement automatically resumed");
            helper.assertTrue(gate.calls == before, "Stopped placement touched the world again");
            sourceUnchanged();
            oneWarning();
        }

        void oneWarning() {
            var fault = IntegrationGuard.faults(Side.SERVER)
                .get("bed_placement/" + owner.getUniqueID());
            helper.assertTrue(
                fault != null && owner.getUniqueID()
                    .equals(fault.owner),
                "Missing scoped failure");
            ProtectionEvents.warn(owner);
            ProtectionEvents.warn(owner);
            var channel = (EmbeddedChannel) owner.playerNetServerHandler.netManager.channel();
            channel.runPendingTasks();
            int warnings = 0;
            Object packet;
            while ((packet = channel.readOutbound()) != null) {
                try {
                    if (packet instanceof S02PacketChat chat) {
                        var bytes = new PacketBuffer(Unpooled.buffer());
                        try {
                            chat.writePacketData(bytes);
                            if (bytes.readStringFromBuffer(32767)
                                .contains(fault.code)) warnings++;
                        } catch (java.io.IOException failure) {
                            throw new IllegalStateException(failure);
                        } finally {
                            bytes.release();
                        }
                    }
                } finally {
                    ReferenceCountUtil.release(packet);
                }
            }
            helper.assertTrue(
                warnings == 1,
                "Expected one actual serialized warning on isolated channel; observed=" + warnings);
        }

        void noDuplicateValue() {
            var pending = new HashSet<>(
                MaidLifecycleData.get(helper.getWorld())
                    .pendingReturnedFilms(helper.getWorld().provider.dimensionId));
            pending.removeAll(initialPending);
            helper.assertTrue(pending.isEmpty(), "Failed placement queued a second item");
            for (Object entity : helper.getWorld().loadedEntityList)
                if (entity instanceof EntityItem item && item.isEntityAlive()
                    && item.posX >= x - 1
                    && item.posX <= x + 2
                    && item.posZ >= z - 1
                    && item.posZ <= z + 3)
                    helper.assertTrue(
                        item.getEntityItem()
                            .getItem() != MaidLifeContent.maidBedItem,
                        "Failed placement spawned a second bed");
        }
    }

    /** 测试插在原生世界通知入口，所有故障在真实世界和Tile表上执行一次 */
    private static final class Gate implements IWorldAccess {

        final Fixture f;
        final String fault;
        int calls;
        TileEntityChest chest;
        ItemStack successor;

        Gate(Fixture f, String fault) {
            this.f = f;
            this.fault = fault;
        }

        @Override
        public void markBlockForUpdate(int x, int y, int z) {
            if (calls > 0 || fault.equals("none")
                || x != f.x
                || y != f.y
                || z != f.z
                || !(f.worldTile() instanceof TileEntityMaidBed)) return;
            calls++;
            if (fault.equals("linkage")) throw new NoSuchMethodError("QA actual world notification API changed");
            if (fault.equals("foreign_tile")) {
                chest = new TileEntityChest();
                chest.setInventorySlotContents(0, f.apple.copy());
                f.helper.getWorld()
                    .setTileEntity(x, y, z, chest);
            }
            if (fault.equals("source")) {
                successor = f.apple.copy();
                f.owner.inventory.mainInventory[f.owner.inventory.currentItem] = successor;
            }
        }

        @Override
        public void markBlockForRenderUpdate(int x, int y, int z) {}

        @Override
        public void markBlockRangeForRenderUpdate(int x, int y, int z, int a, int b, int c) {}

        @Override
        public void playSound(String name, double x, double y, double z, float v, float p) {}

        @Override
        public void playSoundToNearExcept(EntityPlayer p, String n, double x, double y, double z, float v, float q) {}

        @Override
        public void spawnParticle(String n, double x, double y, double z, double a, double b, double c) {}

        @Override
        public void onEntityCreate(Entity entity) {}

        @Override
        public void onEntityDestroy(Entity entity) {}

        @Override
        public void playRecord(String name, int x, int y, int z) {}

        @Override
        public void broadcastSound(int n, int x, int y, int z, int data) {}

        @Override
        public void playAuxSFX(EntityPlayer player, int n, int x, int y, int z, int data) {}

        @Override
        public void destroyBlockPartially(int id, int x, int y, int z, int stage) {}

        @Override
        public void onStaticEntitiesChanged() {}
    }
}
