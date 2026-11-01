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
import com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeIntent;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeSensor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidLifeSelector;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime.InvalidationReason;
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
            // Nutrition等原生平台服务端同步需要真实channel，不建立握手或真实客户端dispatcher
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
            var sensor = new MaidLifeSensor(maid);
            var context = maid.getBrainCoordinator()
                .getPolicyRuntime()
                .getContext();
            var observedCabinet = sensor.scan(context, false)
                .stream()
                .filter(candidate -> candidate.getX() == x && candidate.getY() == y && candidate.getZ() == z)
                .findFirst()
                .orElse(null);
            require(sensor.resolve(observedCabinet, context) == cabinet, "Actual local chunk cabinet observation");
            verifyNoForceLoad(world, maid, sensor, context);
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
                "Actual native cake differs from the six-portion adapter; observed=" + nativePortions);
            world.setBlock(x, y + 1, z, Blocks.cake, 0, 3);
            int hunger = maid.getHunger(), favor = maid.getFavorability();
            life = new MaidLifeController(maid, () -> {}, () -> owner);
            require(
                tick(life, maid) && life.getBites() == 1 && world.getBlockMetadata(x, y + 1, z) == 1,
                "One real cake bite");
            require(
                maid.getHunger() == hunger && maid.getFavorability() == favor + 1,
                "Upstream bite is not an invented hunger meal");
            drive(life, maid, 5);
            require(life.getBites() == 1, "Steal cooldown");
            maid.setSitting(true);
            require(!tick(life, maid) && life.getBites() == 1, "Sit is a hard constraint");
            maid.setSitting(false);
            world.setBlock(x, y, z, Blocks.planks, 0, 3);
            require(sensor.resolve(observedCabinet, context) == null, "Removed cabinet observation must fail");
            var noStand = new MaidLifeController(maid, () -> {}, () -> owner);
            drive(noStand, maid, 40);
            require(
                noStand.getBites() == 0 && world.getBlockMetadata(x, y + 1, z) == 1,
                "Cake without snack cabinet must not be stolen");
            world.setBlock(x, y, z, MaidLifeContent.snackCabinet, 0, 3);
            require(
                sensor.resolve(observedCabinet, context) == null,
                "Replacement cabinet cannot inherit old observation");
            world.setBlockToAir(x, y + 1, z);
            // 原生 Item.cake 的堆叠上限为1，分槽放置以免把原生平台的堆叠补丁当成前提
            verifyIntentSelection(world, maid, owner, x, y, z);
            verifySnackOwnerRange(world, maid, owner, x, y, z);
            maid.getMaidInventory()
                .setInventorySlotContents(0, new ItemStack(Items.cake));
            maid.getMaidInventory()
                .setInventorySlotContents(1, new ItemStack(Items.cake));
            life.cancel();
            life = new MaidLifeController(maid, () -> {}, () -> owner);
            MinecraftForge.EVENT_BUS.register(reject);
            try {
                drive(life, maid, 25);
            } finally {
                MinecraftForge.EVENT_BUS.unregister(reject);
            }
            require(
                reject.seen && life.getPlacements() == 0 && world.isAirBlock(x, y + 1, z) && cakeCount(maid) == 2,
                "Cancelled Forge placement conserves original cake");
            life.cancel();
            for (String mode : new String[] { "policy", "cancel", "reenter", "owner" }) {
                var callbackLife = new MaidLifeController(maid, () -> {}, () -> owner);
                var callback = new RejectPlacement(maid, () -> {
                    if ("policy".equals(mode)) maid.getBrainCoordinator()
                        .getPolicyRuntime()
                        .invalidate(InvalidationReason.RESET);
                    else if ("cancel".equals(mode)) callbackLife.cancel();
                    else if ("owner".equals(mode)) owner.setPosition(x + 8.5, y + 1.5, z + 0.5);
                    else require(!tick(callbackLife, maid), "Reentrant life tick must be refused");
                });
                MinecraftForge.EVENT_BUS.register(callback);
                try {
                    drive(callbackLife, maid, 25);
                    require(
                        callback.calls == 1 && callbackLife.getPlacements() == 0
                            && world.isAirBlock(x, y + 1, z)
                            && cakeCount(maid) == 2,
                        "Uncancelled Forge callback must conserve the cake after " + mode);
                } finally {
                    MinecraftForge.EVENT_BUS.unregister(callback);
                    callbackLife.cancel();
                    owner.setPosition(x + 2.5, y, z + 1.5);
                }
            }
            life = new MaidLifeController(maid, () -> {}, () -> owner);
            drive(life, maid, 25);
            require(
                life.getPlacements() == 1 && cakeCount(maid) == 1 && world.getBlock(x, y + 1, z) == Blocks.cake,
                "Backpack places one real native cake");
            drive(life, maid, 900);
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
            var interrupted = new MaidLifeController(
                maid,
                () -> maid.getBrainCoordinator()
                    .getPolicyRuntime()
                    .invalidate(InvalidationReason.RESET),
                () -> owner);
            require(
                !tick(interrupted, maid) && !interrupted.isSleeping() && bed.available(other),
                "Invalidation during adoption must release the bed and refuse the old observation");
            maid.setHealth(2);
            int sleepFavor = maid.getFavorability();
            life = new MaidLifeController(maid, () -> {}, () -> owner);
            require(
                tick(life, maid) && life.isSleeping()
                    && maid.hasPose(MaidPose.SLEEPING)
                    && bed.isOwnedBy(maid)
                    && (world.getBlockMetadata(x, y, z + 3) & 4) != 0,
                "Bed-owned sleep at DAY rest boundary");
            require(
                maid.getHealth() == maid.getMaxHealth() && maid.getFavorability() == sleepFavor + 2
                    && !maid.getFavorabilityManager()
                        .canAdd("Sleep"),
                "Actual bed entry heals and rewards once");
            competitor = new MaidLifeController(other, () -> {}, () -> owner);
            drive(competitor, other, 25);
            require(!competitor.isSleeping() && bed.isOwnedBy(maid), "Second maid cannot share occupied bed");
            world.setWorldTime(0);
            tick(life, maid);
            require(
                !life.isSleeping() && !maid.hasPose(MaidPose.SLEEPING) && bed.available(other),
                "Dawn wakes and releases bed");
            world.setWorldTime(16000);
            drive(life, maid, 25);
            require(life.isSleeping(), "Return to actual bed");
            maid.setSitting(true);
            tick(life, maid);
            require(
                !life.isSleeping() && maid.isSitting() && bed.available(other),
                "Explicit sit cancels only life-owned sleep");
            maid.setSitting(false);
            drive(life, maid, 25);
            require(life.isSleeping(), "Sleep before unload test");
            bed.onChunkUnload();
            tick(life, maid);
            require(
                !life.isSleeping() && !maid.hasPose(MaidPose.SLEEPING),
                "Actual unload callback clears stale sleeper");
            drive(life, maid, 25);
            require(life.isSleeping(), "Sleep before reload test");
            var bedTag = new NBTTagCompound();
            bed.writeToNBT(bedTag);
            var restoredBed = new TileEntityMaidBed();
            restoredBed.readFromNBT(bedTag);
            world.setTileEntity(x, y, z + 3, restoredBed);
            restoredBed.updateEntity();
            tick(life, maid);
            require(
                !life.isSleeping() && restoredBed.available(other) && (world.getBlockMetadata(x, y, z + 3) & 4) == 0,
                "Reload rejects stale occupancy instead of persisting ghost sleep");
            drive(life, maid, 25);
            require(life.isSleeping(), "Sleep before bed removal test");
            world.setBlockToAir(x, y, z + 3);
            tick(life, maid);
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
            "TLMNH_LIFE_CORE_TEST_PASS cabinet_slots=27 bite=native place=forge_cancel_conserved callback_policy=conserved callback_cancel=conserved callback_reentry=refused callback_owner_range=conserved snack_owner_boundary=strict_8 snack_owner_moved=cancelled recovery=placed_once intent_far=honoured intent_stale=refused intent_yield=no_fallback intent_cancel=refused sleep=paired_bed lease=exclusive dawn=woke unload=callback reload=nbt removal=woke discovery=loaded_chunk_map stale_observation=rejected no_force_load=checked adoption_cancel=released natural_tick=unverified visuals=unverified");
    }

    private static Throwable remember(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    private static void verifySnackOwnerRange(WorldServer world, EntityMaid maid, EntityPlayerMP owner, int x, int y,
        int z) {
        double mx = maid.posX, my = maid.posY, mz = maid.posZ;
        double ox = owner.posX, oy = owner.posY, oz = owner.posZ;
        boolean onGround = maid.onGround;
        MaidLifeController boundary = null, inside = null, moving = null;
        try {
            require(world.setBlock(x, y + 1, z, Blocks.cake, 0, 2), "Owner range cake setup");
            maid.setPosition(x + 1.5, y, z + 0.5);
            owner.setPosition(x + 8.5, y + 1.5, z + 0.5);
            require(maid.getDistanceSqToEntity(owner) < 144, "Owner range must not test the existing 12-block gate");
            boundary = new MaidLifeController(maid, () -> {}, () -> owner);
            require(
                !tick(boundary, maid) && boundary.getBites() == 0 && world.getBlockMetadata(x, y + 1, z) == 0,
                "Cake center at exactly eight blocks from owner must be refused");
            owner.setPosition(x + 8.4999, y + 1.5, z + 0.5);
            inside = new MaidLifeController(maid, () -> {}, () -> owner);
            require(
                tick(inside, maid) && inside.getBites() == 1 && world.getBlockMetadata(x, y + 1, z) == 1,
                "Cake just inside owner range must remain usable");
            inside.cancel();
            world.setBlockMetadataWithNotify(x, y + 1, z, 0, 2);
            maid.setPosition(x + 3.5, y, z + 0.5);
            // 夹具没有推进原生物理，必须给实际石平台上的实体设置着地前提
            maid.onGround = true;
            owner.setPosition(x + 2.5, y, z + 0.5);
            moving = new MaidLifeController(maid, () -> {}, () -> owner);
            require(
                tick(moving, maid) && moving.getBites() == 0
                    && maid.getNavigator()
                        .getPath() != null,
                "Owner range continuation needs an actual approaching action");
            owner.setPosition(x + 8.5, y + 1.5, z + 0.5);
            require(
                !tick(moving, maid) && moving.getBites() == 0
                    && maid.getNavigator()
                        .noPath()
                    && world.getBlockMetadata(x, y + 1, z) == 0,
                "Owner leaving the food range must cancel the owned approach without taking a bite");
        } finally {
            if (boundary != null) boundary.cancel();
            if (inside != null) inside.cancel();
            if (moving != null) moving.cancel();
            world.setBlockToAir(x, y + 1, z);
            maid.setPosition(mx, my, mz);
            maid.onGround = onGround;
            owner.setPosition(ox, oy, oz);
        }
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

    private static void verifyIntentSelection(WorldServer world, EntityMaid maid, EntityPlayerMP owner, int x, int y,
        int z) {
        double px = maid.posX, py = maid.posY, pz = maid.posZ;
        var controllers = new java.util.ArrayList<MaidLifeController>();
        var saved = new MaidLifeIntent[1];
        try {
            require(world.setBlock(x, y + 1, z, Blocks.cake, 0, 2), "Intent near cake setup");
            require(world.setBlock(x + 2, y, z + 1, MaidLifeContent.snackCabinet, 0, 2), "Intent far cabinet setup");
            require(world.setBlock(x + 2, y + 1, z + 1, Blocks.cake, 0, 2), "Intent far cake setup");
            maid.setPosition(x + 1.5, y + 0.25, z + 0.5);
            var far = new MaidLifeController(maid, () -> {}, () -> owner, options -> {
                var nearest = MaidLifeSelector.nearest(options);
                require(
                    nearest != null && nearest.getIntent()
                        .getCandidate()
                        .getX() == x
                        && nearest.getIntent()
                            .getCandidate()
                            .getZ() == z,
                    "Actual default choice must be the near cabinet");
                saved[0] = options.stream()
                    .filter(
                        intent -> intent.getCandidate()
                            .getX() == x + 2
                            && intent.getCandidate()
                                .getZ() == z + 1)
                    .findFirst()
                    .orElse(null);
                require(saved[0] != null, "Far cabinet must be an eligible current intent");
                return new MaidLifeSelector.Selection(saved[0], MaidLifeSelector.Reason.POLICY_CHOICE);
            });
            controllers.add(far);
            require(
                tick(far, maid) && far.getBites() == 1
                    && world.getBlockMetadata(x, y + 1, z) == 0
                    && world.getBlockMetadata(x + 2, y + 1, z + 1) == 1,
                "Execution must honour the exact farther intent rather than reselecting nearest");
            far.cancel();
            for (String mode : new String[] { "stale", "yield", "cancel" }) {
                var refused = new MaidLifeController(maid, () -> {}, () -> owner, options -> {
                    if ("yield".equals(mode)) return null;
                    if ("cancel".equals(mode)) {
                        maid.getBrainCoordinator()
                            .getPolicyRuntime()
                            .invalidate(InvalidationReason.RESET);
                        return MaidLifeSelector.nearest(options);
                    }
                    return new MaidLifeSelector.Selection(saved[0], MaidLifeSelector.Reason.POLICY_CHOICE);
                });
                controllers.add(refused);
                require(
                    !tick(refused, maid) && refused.getBites() == 0
                        && world.getBlockMetadata(x, y + 1, z) == 0
                        && world.getBlockMetadata(x + 2, y + 1, z + 1) == 1,
                    "Refused or empty intent must not fall back to another target: " + mode);
                if ("stale".equals(mode)) require(
                    refused.getStatus() == MaidLifeController.Status.INTENT_REJECTED,
                    "Previous decision's intent must be explicitly rejected");
            }
        } finally {
            for (var controller : controllers) controller.cancel();
            world.setBlockToAir(x, y + 1, z);
            world.setBlockToAir(x + 2, y + 1, z + 1);
            world.setBlockToAir(x + 2, y, z + 1);
            maid.setPosition(px, py, pz);
        }
    }

    private static void verifyNoForceLoad(WorldServer world, EntityMaid maid, MaidLifeSensor sensor,
        MaidPolicyContext context) {
        double x = maid.posX, y = maid.posY, z = maid.posZ;
        int loaded = world.theChunkProviderServer.loadedChunks.size();
        try {
            maid.setPosition(1_000_000.5, y, 1_000_000.5);
            int min = 999_995 >> 4, max = 1_000_005 >> 4;
            for (int cx = min; cx <= max; cx++) for (int cz = min; cz <= max; cz++)
                require(!world.theChunkProviderServer.chunkExists(cx, cz), "No-force fixture requires unloaded chunks");
            require(
                sensor.scan(context, false)
                    .isEmpty(),
                "Unloaded region cannot yield observed candidates");
            require(world.theChunkProviderServer.loadedChunks.size() == loaded, "Discovery must not load any chunk");
        } finally {
            maid.setPosition(x, y, z);
        }
    }

    private static boolean tick(MaidLifeController life, EntityMaid maid) {
        var context = maid.getBrainCoordinator()
            .getPolicyRuntime()
            .getContext();
        return life.tick(context, context.getToken(), true);
    }

    private static void drive(MaidLifeController life, EntityMaid maid, int ticks) {
        for (int index = 0; index < ticks; index++) tick(life, maid);
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
        private final Runnable callback;
        private boolean seen;
        private int calls;

        private RejectPlacement(EntityMaid maid) {
            this(maid, null);
        }

        private RejectPlacement(EntityMaid maid, Runnable callback) {
            this.maid = maid;
            this.callback = callback;
        }

        @SubscribeEvent
        public void place(BlockEvent.PlaceEvent event) {
            if (event.player.getUniqueID()
                .equals(maid.getUniqueID())) {
                seen = true;
                if (++calls > 1 && callback != null) throw new IllegalStateException("Repeated callback placement");
                if (callback == null) event.setCanceled(true);
                else callback.run();
            }
        }
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new IllegalStateException("TLMNH life core probe failed: " + description);
    }
}
