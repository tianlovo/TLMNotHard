package com.tianluoqaq.tlmnh.common.development;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.ai.MaidBoundedPathPlanner;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.PathResult;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.Status;
import com.tianluoqaq.tlmnh.common.ai.MaidWorldNavigationBudget;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime.InvalidationReason;
import com.tianluoqaq.tlmnh.common.config.MaidNavigationConfig;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** 先测原生调用，再以真实世界 tick 验证共享额度、等待、拾取与取消 */
public final class MaidNavigationBudgetProbe {

    private static MaidNavigationBudgetProbe pending;
    private final WorldServer world;
    private final List<EntityMaid> followers = new ArrayList<>();
    private EntityPlayerMP owner;
    private EntityMaid pickup, control;
    private EntityItem item;
    private int x, z, ticks, savedLimit, maxUsed;
    private boolean floorReady, configured, finished, pickupWait, followWait;

    private MaidNavigationBudgetProbe(WorldServer world) {
        this.world = world;
    }

    public static void start(WorldServer world) {
        require(
            Boolean.getBoolean("tlmnh.devCommands") && world.provider.dimensionId == 0 && pending == null,
            "Dedicated overworld and one episode required");
        var probe = new MaidNavigationBudgetProbe(world);
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
        for (Object value : world.loadedEntityList)
            require(!(value instanceof EntityMaid maid) || maid.isDead, "No other maid in this fixture world");
        var spawn = world.getSpawnPoint();
        x = spawn.posX + 12;
        z = spawn.posZ + 88;
        for (int px = x - 40; px <= x + 40; px += 8)
            for (int pz = z - 32; pz <= z + 32; pz += 8) world.getChunkFromBlockCoords(px, pz);
        for (int px = x - 24; px <= x + 24; px++) for (int pz = z - 12; pz <= z + 12; pz++)
            for (int py = 99; py <= 104; py++) require(world.isAirBlock(px, py, pz), "Empty test volume required");
        floorReady = true;
        for (int px = x - 24; px <= x + 24; px++)
            for (int pz = z - 12; pz <= z + 12; pz++) world.setBlock(px, 99, pz, Blocks.stone, 0, 2);
        savedLimit = MaidNavigationConfig.queriesPerTick;
        MaidNavigationConfig.queriesPerTick = 1;
        configured = true;
        MaidWorldNavigationBudget.unload(world);
        owner = new EntityPlayerMP(
            MinecraftServer.getServer(),
            world,
            new GameProfile(UUID.fromString("335da31d-b5fc-48b8-b764-0fb05e164bda"), "[TLMNH-Nav]"),
            new ItemInWorldManager(world));
        owner.theItemInWorldManager.setGameType(GameType.SURVIVAL);
        owner.setPosition(x + 10.5, 100, z + .5);
        world.playerEntities.add(owner);
        for (int i = 0; i < 16; i++) followers.add(actor(x - 8 + (i % 4) * 2, z - 3 + (i / 4) * 2));
        control = actor(x - 12, z - 7);
        long total = 0, longest = 0;
        var nativePlanner = new MaidBoundedPathPlanner();
        for (var maid : followers) {
            long start = System.nanoTime();
            var created = nativePlanner.create(maid, maid.posX + 8, 100, maid.posZ, .6);
            long elapsed = System.nanoTime() - start;
            total += elapsed;
            longest = Math.max(longest, elapsed);
            require(created.queryExecuted && created.path != null, "Measured baseline must execute native queries");
        }
        TLMNotHard.LOG.info(
            "TLMNH_NAV_BUDGET_BASELINE controlled_queries=16 same_tick=true total_ns={} max_ns={} benchmark=false",
            total,
            longest);
        control.getBrainCoordinator()
            .getPolicyRuntime();
        var old = nativePlanner.create(control, control.posX + 8, 100, control.posZ, .6);
        require(old.queryExecuted && old.path != null, "Stale query fixture required");
        control.getBrainCoordinator()
            .getPolicyRuntime()
            .invalidate(InvalidationReason.RESET);
        var refused = nativePlanner.install(control, old);
        require(
            refused.result == PathResult.STALE_REQUEST && !refused.nativeSetPathCalled,
            "Invalidated creation must not install an old path");
        var scheduled = new MaidBoundedPathPlanner(true);
        require(
            scheduled.request(control, control.posX + 8, 100, control.posZ, .6) == PathResult.STARTED,
            "First world permit must execute native navigation");
        var path = control.getNavigator()
            .getPath();
        require(
            scheduled.request(control, control.posX + 7, 100, control.posZ, .6) == PathResult.WAITING_BUDGET
                && control.getNavigator()
                    .getPath() == path,
            "Budget denial must preserve the installed path");
        control.getNavigator()
            .clearPathEntity();
        MaidWorldNavigationBudget.cancel(control);
        pickup = actor(x + 8, z);
        pickup.setPickup(true);
        item = new EntityItem(world, x + 11.5, 100, z + .5, new ItemStack(Items.diamond, 4));
        item.delayBeforeCanPickup = 0;
        require(world.spawnEntityInWorld(item), "Registered pickup target required");
        TLMNotHard.LOG.info(
            "TLMNH_NAV_BUDGET_STARTED natural_followers=16 pickup=1 fixture_limit=1 default_limit={}",
            savedLimit);
    }

    private EntityMaid actor(double px, double pz) {
        var maid = new EntityMaid(world);
        maid.getBrainCoordinator()
            .setSuspended(true);
        require(maid.bindTo(owner.getUniqueID()), "Bound owner required");
        maid.setPickup(false);
        maid.setPosition(px + .5, 100, pz + .5);
        maid.onGround = true;
        require(world.spawnEntityInWorld(maid), "Registered navigation actor required");
        return maid;
    }

    @SubscribeEvent
    public void tick(TickEvent.WorldTickEvent event) {
        if (finished || event.world != world) return;
        if (event.phase != TickEvent.Phase.END) return;
        try {
            ticks++;
            if (ticks <= 3) {
                if (ticks == 3) {
                    for (var maid : followers) {
                        require(maid.onGround, "Followers must settle on the actual floor before activation");
                        maid.getBrainCoordinator()
                            .setSuspended(false);
                    }
                    require(pickup.onGround, "Pickup actor must settle before activation");
                    pickup.getBrainCoordinator()
                        .setSuspended(false);
                    TLMNotHard.LOG.info("TLMNH_NAV_BUDGET_PHASE_PASS settled_actors=17 warmup_ticks=3");
                }
                return;
            }
            var budget = MaidWorldNavigationBudget.forWorld(world);
            int used = budget.used(world.getTotalWorldTime());
            maxUsed = Math.max(maxUsed, used);
            require(used <= 1, "Per-world native admission limit exceeded");
            for (var maid : followers) {
                var follow = maid.getBrainCoordinator()
                    .getFollowController();
                followWait |= follow.getStatus() == Status.NAVIGATION_PENDING;
                require(
                    follow.getPathFailures() == 0,
                    "Budget waiting must not count as a failed route: result=" + follow.getLastPathResult()
                        + " status="
                        + follow.getStatus()
                        + " ground="
                        + maid.onGround
                        + " attempt="
                        + (maid.getBrainCoordinator()
                            .getLastPathAttempt() == null ? "none"
                                : maid.getBrainCoordinator()
                                    .getLastPathAttempt().diagnostic));
            }
            pickupWait |= pickup.getBrainCoordinator()
                .getPickupController()
                .isWaitingForPath();
            if (ticks == 93) {
                for (var maid : followers) require(
                    maid.getBrainCoordinator()
                        .getFollowController()
                        .getPathRequests() > 0,
                    "Every persistent follower must receive a native path permit");
                int diamonds = 0;
                var inventory = pickup.getMaidInventory();
                for (int i = 0; i < inventory.getSizeInventory(); i++) {
                    var stack = inventory.getStackInSlot(i);
                    if (stack != null && stack.getItem() == Items.diamond) diamonds += stack.stackSize;
                }
                require(followWait && pickupWait && budget.deferred() > 0, "Actual waiting must be observed");
                require(diamonds == 4 && item.isDead, "Deferred pickup must recover without ignoring its target");
                TLMNotHard.LOG.info(
                    "TLMNH_NAV_BUDGET_PHASE_PASS followers=16 max_used={} admitted={} deferred={} diamonds={}",
                    maxUsed,
                    budget.admitted(),
                    budget.deferred(),
                    diamonds);
                finish(
                    true,
                    "quota;fifo;all-followers-served;pickup-wait-recovery;stale-install;existing-path-preserved");
            }
        } catch (RuntimeException | Error failure) {
            finish(false, failure.toString());
        }
    }

    private void finish(boolean passed, String detail) {
        if (finished) return;
        finished = true;
        FMLCommonHandler.instance()
            .bus()
            .unregister(this);
        var actors = new ArrayList<>(followers);
        if (pickup != null) actors.add(pickup);
        if (control != null) actors.add(control);
        for (var maid : actors) {
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
        if (floorReady) for (int px = x - 24; px <= x + 24; px++)
            for (int pz = z - 12; pz <= z + 12; pz++) world.setBlockToAir(px, 99, pz);
        if (configured) {
            MaidNavigationConfig.queriesPerTick = savedLimit;
            MaidWorldNavigationBudget.unload(world);
        }
        pending = null;
        TLMNotHard.LOG.info(
            "TLMNH_NAV_BUDGET_TEST_{} ticks={} detail={} benchmark=unverified",
            passed ? "PASS" : "FAIL",
            ticks,
            detail);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
