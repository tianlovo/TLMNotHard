package com.tianluoqaq.tlmnh.common.development;

import java.util.UUID;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeCandidate;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeIntent;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner.Owner;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 普通Root中真实拾取/偷吃竞争、范围变化和坐下取消，不代替完整O/F/N */
public final class MaidIntentRuntimeProbe {

    private static MaidIntentRuntimeProbe pending;
    private final WorldServer world;
    private EntityPlayerMP owner;
    private EntityMaid maid;
    private EntityItem item;
    private EmbeddedChannel connection;
    private int x, z, ticks, phase, phaseStart, bitesAtMove;
    private long savedTime;
    private boolean floorReady, finished, sawPickup, sawSnack, staleChecked, sawCompetition;

    private MaidIntentRuntimeProbe(WorldServer world) {
        this.world = world;
    }

    public static void start(WorldServer world) {
        require(
            Boolean.getBoolean("tlmnh.devCommands") && world.provider.dimensionId == 0 && pending == null,
            "Dedicated overworld and one episode required");
        var probe = new MaidIntentRuntimeProbe(world);
        pending = probe;
        try {
            probe.begin();
            FMLCommonHandler.instance()
                .bus()
                .register(probe);
        } catch (RuntimeException | Error failure) {
            probe.finish(false, failure.toString());
            throw failure;
        }
    }

    private void begin() {
        var spawn = world.getSpawnPoint();
        x = spawn.posX + 48;
        z = spawn.posZ + 56;
        for (int px = x - 32; px <= x + 32; px += 8)
            for (int pz = z - 32; pz <= z + 32; pz += 8) world.getChunkFromBlockCoords(px, pz);
        for (int px = x - 10; px <= x + 10; px++) for (int pz = z - 6; pz <= z + 6; pz++)
            for (int py = 99; py <= 103; py++) require(world.isAirBlock(px, py, pz), "Empty test volume required");
        floorReady = true;
        savedTime = world.getWorldTime();
        world.setWorldTime(0);
        for (int px = x - 10; px <= x + 10; px++)
            for (int pz = z - 6; pz <= z + 6; pz++) world.setBlock(px, 99, pz, Blocks.stone, 0, 2);
        require(world.setBlock(x + 5, 100, z, MaidLifeContent.snackCabinet, 0, 3), "Actual snack cabinet required");
        require(world.setBlock(x + 5, 101, z, Blocks.cake, 0, 3), "Native cake required");
        owner = new EntityPlayerMP(
            MinecraftServer.getServer(),
            world,
            new GameProfile(UUID.fromString("038ac80c-3325-44a8-838c-61eb04b202df"), "[TLMNH-Intent]"),
            new ItemInWorldManager(world));
        var manager = new NetworkManager(false);
        connection = new EmbeddedChannel(manager);
        manager.setConnectionState(EnumConnectionState.PLAY);
        IsolatedPlayerChannel.verify(manager, connection);
        new NetHandlerPlayServer(MinecraftServer.getServer(), manager, owner);
        owner.theItemInWorldManager.setGameType(GameType.SURVIVAL);
        owner.setPosition(x - 1.5, 100, z + .5);
        world.playerEntities.add(owner);
        maid = new EntityMaid(world);
        maid.getBrainCoordinator()
            .setSuspended(true);
        require(maid.bindTo(owner.getUniqueID()), "Bound owner required");
        maid.setSchedule(MaidSchedule.DAY);
        maid.setPickup(true);
        maid.setPosition(x + .5, 100, z + .5);
        require(world.spawnEntityInWorld(maid), "Registered actor required");
        item = new EntityItem(world, x + 2.8, 100, z + .5, new ItemStack(Items.diamond, 4));
        item.delayBeforeCanPickup = 0;
        require(world.spawnEntityInWorld(item), "Registered pickup required");
        item.motionX = item.motionZ = 0;
        TLMNotHard.LOG.info("TLMNH_INTENT_RUNTIME_STARTED natural_world_ticks=true");
    }

    @SubscribeEvent
    public void tick(TickEvent.WorldTickEvent event) {
        if (finished || event.world != world || event.phase != TickEvent.Phase.END) return;
        try {
            ticks++;
            var brain = maid.getBrainCoordinator();
            var life = brain.getLifeController();
            if (ticks == 3) {
                require(maid.onGround, "Natural floor contact before activation");
                require(
                    maid.canPickup(item, true) && maid.getEntitySenses()
                        .canSee(item) && owner.getDistanceSqToEntity(item) <= 25,
                    "Pickup must be legal at activation: delay=" + item.delayBeforeCanPickup
                        + " owner_distance_sq="
                        + owner.getDistanceSqToEntity(item));
                brain.setSuspended(false);
                phaseStart = ticks;
            }
            if (ticks <= 3) return;
            sawPickup |= brain.getOrdinaryIntent() == Kind.PICKUP;
            sawSnack |= brain.getOrdinaryIntent() == Kind.SNACK;
            sawCompetition |= brain.offeredOrdinaryIntent(Kind.SNACK) && brain.offeredOrdinaryIntent(Kind.PICKUP);
            if (phase == 1 && !staleChecked
                && brain.getOrdinaryIntent() == Kind.SNACK
                && brain.getActionRunner()
                    .getOwner() == Owner.LIFE
                && life.getBites() == 0) {
                var context = brain.getPolicyRuntime()
                    .getContext();
                var wrong = new MaidLifeCandidate(
                    MaidLifeCandidate.Kind.SNACK,
                    maid.dimension,
                    x + 6,
                    100,
                    z,
                    world.getTotalWorldTime(),
                    world,
                    context,
                    world.getTileEntity(x + 5, 100, z));
                var oldRun = brain.getActionRunner()
                    .getCurrentRun();
                require(
                    !life.tickSelected(
                        context,
                        context.getToken(),
                        new MaidLifeIntent(MaidLifeIntent.Action.STEAL, wrong, 1))
                        && life.getBites() == 0
                        && brain.getActionRunner()
                            .getCurrentRun() == oldRun,
                    "Wrong selected goal cannot advance or cancel a different active life action");
                staleChecked = true;
            }
            if (phase == 0 && diamonds() == 4) {
                require(
                    sawCompetition && sawPickup && life.getBites() == 0 && item.isDead,
                    "Near pickup must win before farther snack: simultaneous=" + sawCompetition
                        + " selected_pickup="
                        + sawPickup
                        + " bites="
                        + life.getBites()
                        + " item_dead="
                        + item.isDead
                        + " choice="
                        + brain.getOrdinaryIntent()
                        + " item_owner_sq="
                        + owner.getDistanceSqToEntity(item));
                phase = 1;
                phaseStart = ticks;
                TLMNotHard.LOG.info("TLMNH_INTENT_PHASE_PASS phase=pickup ticks={} diamonds=4", ticks);
            } else if (phase == 1 && life.getBites() > 0) {
                require(
                    sawSnack && staleChecked
                        && world.getBlock(x + 5, 101, z) == Blocks.cake
                        && world.getBlockMetadata(x + 5, 101, z) > 0,
                    "Deferred snack must execute native bite");
                bitesAtMove = life.getBites();
                owner.setPosition(x - 20, 100, z + .5);
                phase = 2;
                phaseStart = ticks;
                TLMNotHard.LOG.info("TLMNH_INTENT_PHASE_PASS phase=snack ticks={} bites={}", ticks, bitesAtMove);
            } else if (phase == 2 && ticks - phaseStart >= 12) {
                require(
                    brain.getOrdinaryIntent() == Kind.FOLLOW && brain.getActionRunner()
                        .getOwner() == Owner.FOLLOW && life.getBites() == bitesAtMove,
                    "Owner boundary must revoke snack and activate follow");
                maid.setSitting(true);
                phase = 3;
                phaseStart = ticks;
            } else if (phase == 3 && ticks - phaseStart >= 6) {
                require(
                    brain.getOrdinaryIntent() == null && brain.getActionRunner()
                        .getOwner() == null && diamonds() == 4 && life.getBites() == bitesAtMove,
                    "Sitting leaves no intent, lease or extra consumption");
                finish(true, "near-pickup;snack-recovery;native-bite;owner-boundary-follow;sitting;resources");
            }
            require(
                ticks - phaseStart <= 140,
                "Intent phase timeout: phase=" + phase
                    + " choice="
                    + brain.getOrdinaryIntent()
                    + " life="
                    + life.getStatus()
                    + " pickup="
                    + brain.getPickupController()
                        .getTarget());
        } catch (RuntimeException | Error failure) {
            finish(false, failure.toString());
        }
    }

    private int diamonds() {
        int count = 0;
        var inventory = maid.getMaidInventory();
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            var stack = inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() == Items.diamond) count += stack.stackSize;
        }
        return count;
    }

    private void finish(boolean passed, String detail) {
        if (finished) return;
        finished = true;
        FMLCommonHandler.instance()
            .bus()
            .unregister(this);
        if (maid != null) {
            maid.getBrainCoordinator()
                .setSuspended(true);
            world.removeEntity(maid);
            maid.setDead();
        }
        if (item != null) item.setDead();
        if (owner != null) {
            world.playerEntities.remove(owner);
            owner.setDead();
        }
        if (connection != null) {
            connection.finish();
            Object message;
            while ((message = connection.readOutbound()) != null) ReferenceCountUtil.release(message);
            while ((message = connection.readInbound()) != null) ReferenceCountUtil.release(message);
        }
        if (floorReady) {
            world.setBlockToAir(x + 5, 101, z);
            world.setBlockToAir(x + 5, 100, z);
            for (int px = x - 10; px <= x + 10; px++)
                for (int pz = z - 6; pz <= z + 6; pz++) world.setBlockToAir(px, 99, pz);
            world.setWorldTime(savedTime);
        }
        pending = null;
        TLMNotHard.LOG.info(
            "TLMNH_INTENT_RUNTIME_TEST_{} ticks={} detail={} benchmark=unverified",
            passed ? "PASS" : "FAIL",
            ticks,
            detail);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
