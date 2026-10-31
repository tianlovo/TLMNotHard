package com.tianluoqaq.tlmnh.common.development;

import java.util.HashSet;
import java.util.UUID;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.life.MaidLifeController;
import com.tianluoqaq.tlmnh.common.life.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.life.TileEntitySnackCabinet;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 真实方块/背包/Forge 放置事件夹具，手动推进控制器，不声称自然 tick 或客户端画面通过 */
public final class MaidLifeProbe {

    private MaidLifeProbe() {}

    public static void run(WorldServer world) {
        require(Boolean.getBoolean("tlmnh.devCommands") && !AnimationLab.isLab(world), "Test overworld required");
        var spawn = world.getSpawnPoint();
        int x = (spawn.posX >> 4 << 4) + 8, z = (spawn.posZ >> 4 << 4) + 8;
        int y = Math.min(world.getHeight() - 6, Math.max(90, spawn.posY + 8));
        require(
            world.getChunkProvider()
                .chunkExists(x >> 4, z >> 4),
            "Fixture chunk must already be loaded");
        for (int dx = -2; dx <= 3; dx++) for (int dz = -2; dz <= 4; dz++) for (int dy = -1; dy <= 3; dy++)
            require(world.isAirBlock(x + dx, y + dy, z + dz), "Fixture requires an empty bounded volume");
        var existingEntities = new HashSet<>(world.loadedEntityList);
        long time = world.getWorldTime();
        var owner = new EntityPlayerMP(
            MinecraftServer.getServer(),
            world,
            new GameProfile(UUID.fromString("b4ced159-49d2-43b3-a0ad-d928a8f990d4"), "TLMNHLifeFixture"),
            new ItemInWorldManager(world));
        var maid = new EntityMaid(world);
        var other = new EntityMaid(world);
        MaidLifeController life = null, competitor = null;
        var reject = new RejectPlacement(maid);
        var manager = new NetworkManager(false);
        EmbeddedChannel connection = null;
        Throwable failure = null;
        try {
            // Nutrition等RC2服务端同步需要真实channel，不建立握手或真实客户端dispatcher
            connection = new EmbeddedChannel(manager);
            manager.setConnectionState(EnumConnectionState.PLAY);
            IsolatedPlayerChannel.verify(manager, connection);
            new NetHandlerPlayServer(MinecraftServer.getServer(), manager, owner);
            owner.theItemInWorldManager.setGameType(GameType.SURVIVAL);
            owner.setPosition(x + 2.5, y, z + 1.5);
            for (int dx = -2; dx <= 3; dx++)
                for (int dz = -2; dz <= 4; dz++) world.setBlock(x + dx, y - 1, z + dz, Blocks.stone, 0, 2);
            world.playerEntities.add(owner);
            maid.bindTo(owner.getUniqueID());
            other.bindTo(owner.getUniqueID());
            maid.getBrainCoordinator()
                .setSuspended(true);
            other.getBrainCoordinator()
                .setSuspended(true);
            maid.setSchedule(MaidSchedule.DAY);
            other.setSchedule(MaidSchedule.DAY);
            maid.setPosition(x + 1.5, y, z + 0.5);
            other.setPosition(x + 2.5, y, z + 2.5);
            require(world.spawnEntityInWorld(maid) && world.spawnEntityInWorld(other), "Spawn actual fixture maids");
            world.setWorldTime(0);
            require(world.setBlock(x, y, z, MaidLifeContent.snackCabinet, 0, 3), "Actual cabinet placement");
            var cabinet = (TileEntitySnackCabinet) world.getTileEntity(x, y, z);
            cabinet.setInventorySlotContents(26, new ItemStack(Items.apple, 3));
            var stored = new NBTTagCompound();
            cabinet.writeToNBT(stored);
            var reloadedCabinet = new TileEntitySnackCabinet();
            reloadedCabinet.readFromNBT(stored);
            require(
                reloadedCabinet.getSizeInventory() == 27 && reloadedCabinet.getStackInSlot(26).stackSize == 3,
                "Cabinet NBT conservation");
            cabinet.setInventorySlotContents(26, null);
            world.setBlock(x, y + 1, z, Blocks.cake, 0, 3);
            int nativePortions = 0;
            var nativeMetadata = new java.util.ArrayList<Integer>();
            while (world.getBlock(x, y + 1, z) == Blocks.cake && nativePortions < 16) {
                nativeMetadata.add(world.getBlockMetadata(x, y + 1, z));
                var hunger = new NBTTagCompound();
                owner.getFoodStats()
                    .writeNBT(hunger);
                hunger.setInteger("foodLevel", 0);
                owner.getFoodStats()
                    .readNBT(hunger);
                Blocks.cake.onBlockActivated(world, x, y + 1, z, owner, 1, 0.5F, 0.5F, 0.5F);
                nativePortions++;
            }
            TLMNotHard.LOG.info(
                "TLMNH_LIFE_NATIVE_CAKE_OBSERVED portions={} metadata={} block_class={}",
                nativePortions,
                nativeMetadata,
                Blocks.cake.getClass()
                    .getName());
            require(
                nativePortions == 6 && world.isAirBlock(x, y + 1, z),
                "Actual RC2 cake differs from the six-portion adapter; observed=" + nativePortions);
            world.setBlock(x, y + 1, z, Blocks.cake, 0, 3);
            int hunger = maid.getHunger(), favor = maid.getFavorability();
            life = new MaidLifeController(maid, () -> {}, () -> owner);
            require(
                life.tick(true) && life.getBites() == 1 && world.getBlockMetadata(x, y + 1, z) == 1,
                "One real cake bite");
            require(
                maid.getHunger() == hunger && maid.getFavorability() == favor + 1,
                "Upstream bite is not an invented hunger meal");
            drive(life, 5);
            require(life.getBites() == 1, "Steal cooldown");
            maid.setSitting(true);
            require(!life.tick(true) && life.getBites() == 1, "Sit is a hard constraint");
            maid.setSitting(false);
            world.setBlock(x, y, z, Blocks.planks, 0, 3);
            var noStand = new MaidLifeController(maid, () -> {}, () -> owner);
            drive(noStand, 40);
            require(
                noStand.getBites() == 0 && world.getBlockMetadata(x, y + 1, z) == 1,
                "Cake without snack cabinet must not be stolen");
            world.setBlock(x, y, z, MaidLifeContent.snackCabinet, 0, 3);
            world.setBlockToAir(x, y + 1, z);
            // 原生 Item.cake 的堆叠上限为1，分槽放置以免把RC2的堆叠补丁当成前提
            maid.getMaidInventory()
                .setInventorySlotContents(0, new ItemStack(Items.cake));
            maid.getMaidInventory()
                .setInventorySlotContents(1, new ItemStack(Items.cake));
            life.cancel();
            life = new MaidLifeController(maid, () -> {}, () -> owner);
            MinecraftForge.EVENT_BUS.register(reject);
            try {
                drive(life, 25);
            } finally {
                MinecraftForge.EVENT_BUS.unregister(reject);
            }
            require(
                reject.seen && life.getPlacements() == 0 && world.isAirBlock(x, y + 1, z) && cakeCount(maid) == 2,
                "Cancelled Forge placement conserves original cake");
            life.cancel();
            life = new MaidLifeController(maid, () -> {}, () -> owner);
            drive(life, 25);
            require(
                life.getPlacements() == 1 && cakeCount(maid) == 1 && world.getBlock(x, y + 1, z) == Blocks.cake,
                "Backpack places one real native cake");
            drive(life, 900);
            require(
                life.getBites() == 1 && world.getBlockMetadata(x, y + 1, z) == 1 && maid.getFavorability() == favor + 1,
                "Placed cake can be stolen; reward cooldown persists across controllers");
            life.cancel();
            owner.rotationYaw = 0;
            var bedItem = new ItemStack(MaidLifeContent.maidBedItem);
            require(
                bedItem.getItem()
                    .onItemUse(bedItem, owner, world, x, y - 1, z + 2, 1, 0.5F, 1, 0.5F) && bedItem.stackSize == 0,
                "Actual two-part bed item placement");
            var bed = (TileEntityMaidBed) world.getTileEntity(x, y, z + 3);
            require(bed != null && bed.complete(), "Actual paired bed head");
            maid.setPosition(x + 1.5, y, z + 3.5);
            world.setWorldTime(16000);
            life = new MaidLifeController(maid, () -> {}, () -> owner);
            require(
                life.tick(true) && life.isSleeping()
                    && maid.hasPose(MaidPose.SLEEPING)
                    && bed.isOwnedBy(maid)
                    && (world.getBlockMetadata(x, y, z + 3) & 4) != 0,
                "Bed-owned sleep at DAY rest boundary");
            competitor = new MaidLifeController(other, () -> {}, () -> owner);
            drive(competitor, 25);
            require(!competitor.isSleeping() && bed.isOwnedBy(maid), "Second maid cannot share occupied bed");
            world.setWorldTime(0);
            life.tick(true);
            require(
                !life.isSleeping() && !maid.hasPose(MaidPose.SLEEPING) && bed.available(other),
                "Dawn wakes and releases bed");
            world.setWorldTime(16000);
            drive(life, 25);
            require(life.isSleeping(), "Return to actual bed");
            maid.setSitting(true);
            life.tick(true);
            require(
                !life.isSleeping() && maid.isSitting() && bed.available(other),
                "Explicit sit cancels only life-owned sleep");
            maid.setSitting(false);
            drive(life, 25);
            require(life.isSleeping(), "Sleep before unload test");
            bed.onChunkUnload();
            life.tick(true);
            require(
                !life.isSleeping() && !maid.hasPose(MaidPose.SLEEPING),
                "Actual unload callback clears stale sleeper");
            drive(life, 25);
            require(life.isSleeping(), "Sleep before reload test");
            var bedTag = new NBTTagCompound();
            bed.writeToNBT(bedTag);
            var restoredBed = new TileEntityMaidBed();
            restoredBed.readFromNBT(bedTag);
            world.setTileEntity(x, y, z + 3, restoredBed);
            restoredBed.updateEntity();
            life.tick(true);
            require(
                !life.isSleeping() && restoredBed.available(other) && (world.getBlockMetadata(x, y, z + 3) & 4) == 0,
                "Reload rejects stale occupancy instead of persisting ghost sleep");
            drive(life, 25);
            require(life.isSleeping(), "Sleep before bed removal test");
            world.setBlockToAir(x, y, z + 3);
            life.tick(true);
            require(!life.isSleeping() && !maid.hasPose(MaidPose.SLEEPING), "Removing bed wakes sleeper");
        } catch (RuntimeException | Error error) {
            failure = error;
        } finally {
            try {
                try {
                    MinecraftForge.EVENT_BUS.unregister(reject);
                } catch (RuntimeException | Error error) {
                    failure = remember(failure, error);
                }
                try {
                    if (life != null) life.cancel();
                } catch (RuntimeException | Error error) {
                    failure = remember(failure, error);
                }
                try {
                    if (competitor != null) competitor.cancel();
                } catch (RuntimeException | Error error) {
                    failure = remember(failure, error);
                }
                try {
                    maid.getMaidInventory()
                        .setInventorySlotContents(0, null);
                    maid.getMaidInventory()
                        .setInventorySlotContents(1, null);
                    maid.setDead();
                    world.removeEntity(maid);
                } catch (RuntimeException | Error error) {
                    failure = remember(failure, error);
                }
                try {
                    other.setDead();
                    world.removeEntity(other);
                } catch (RuntimeException | Error error) {
                    failure = remember(failure, error);
                }
                try {
                    world.playerEntities.remove(owner);
                } catch (RuntimeException | Error error) {
                    failure = remember(failure, error);
                }
                try {
                    for (int dx = -2; dx <= 3; dx++) for (int dz = -2; dz <= 4; dz++)
                        for (int dy = 3; dy >= -1; dy--) world.setBlock(x + dx, y + dy, z + dz, Blocks.air, 0, 2);
                } catch (RuntimeException | Error error) {
                    failure = remember(failure, error);
                }
                try {
                    for (Object value : new java.util.ArrayList<>(world.loadedEntityList)) {
                        if (value instanceof EntityItem item && !existingEntities.contains(item)
                            && Math.abs(item.posX - x) < 5
                            && Math.abs(item.posZ - z) < 7
                            && Math.abs(item.posY - y) < 5) world.removeEntity(item);
                    }
                } catch (RuntimeException | Error error) {
                    failure = remember(failure, error);
                }
                try {
                    world.setWorldTime(time);
                } catch (RuntimeException | Error error) {
                    failure = remember(failure, error);
                }
            } finally {
                // 控制器、实体或方块清理失败，也必须关闭连接并释放所有已排队消息
                if (connection != null) {
                    try {
                        IsolatedPlayerChannel.requireUnbound(connection);
                    } catch (RuntimeException | Error error) {
                        failure = remember(failure, error);
                    }
                    try {
                        connection.finish();
                    } catch (RuntimeException | Error error) {
                        failure = remember(failure, error);
                    }
                    try {
                        failure = releaseMessages(connection, true, failure);
                    } catch (RuntimeException | Error error) {
                        failure = remember(failure, error);
                    }
                    try {
                        failure = releaseMessages(connection, false, failure);
                    } catch (RuntimeException | Error error) {
                        failure = remember(failure, error);
                    }
                }
            }
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
        TLMNotHard.LOG.info(
            "TLMNH_LIFE_CORE_TEST_PASS cabinet_slots=27 bite=native place=forge_cancel_conserved sleep=paired_bed lease=exclusive dawn=woke unload=callback reload=nbt removal=woke natural_tick=unverified visuals=unverified");
    }

    private static Throwable remember(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    private static Throwable releaseMessages(EmbeddedChannel connection, boolean outbound, Throwable failure) {
        Object message;
        while ((message = outbound ? connection.readOutbound() : connection.readInbound()) != null) {
            try {
                ReferenceCountUtil.release(message);
            } catch (RuntimeException | Error error) {
                failure = remember(failure, error);
            }
        }
        return failure;
    }

    private static void drive(MaidLifeController life, int ticks) {
        for (int index = 0; index < ticks; index++) life.tick(true);
    }

    private static int cakeCount(EntityMaid maid) {
        int total = 0;
        for (int slot = 0; slot < com.tianluoqaq.tlmnh.common.inventory.MaidInventory.BACKPACK_SIZE; slot++) {
            var stack = maid.getMaidInventory()
                .getStackInSlot(slot);
            if (stack != null && stack.getItem() == Items.cake) total += stack.stackSize;
        }
        return total;
    }

    public static final class RejectPlacement {

        private final EntityMaid maid;
        private boolean seen;

        private RejectPlacement(EntityMaid maid) {
            this.maid = maid;
        }

        @SubscribeEvent
        public void place(BlockEvent.PlaceEvent event) {
            if (event.player.getUniqueID()
                .equals(maid.getUniqueID())) {
                seen = true;
                event.setCanceled(true);
            }
        }
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new IllegalStateException("TLMNH life core probe failed: " + description);
    }
}
