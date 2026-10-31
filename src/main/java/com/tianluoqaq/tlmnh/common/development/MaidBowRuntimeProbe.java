package com.tianluoqaq.tlmnh.common.development;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.development.telemetry.MaidEpisodeRecorder;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 自然服务端 tick 的弓箭实验；脚本只提供场景输入，不推进实体、箭或行为控制器 */
public final class MaidBowRuntimeProbe {

    private enum Phase {
        SETTLE,
        APPROACH,
        MOVING,
        FRIEND,
        RECOVERY,
        INTERRUPT_DRAW,
        SITTING,
        RESUME
    }

    private static MaidBowRuntimeProbe pending;
    private final WorldServer world;
    private final boolean recordingEnabled;
    private MaidEpisodeRecorder recorder;
    private final EntityPlayerMP owner;
    private final EntityMaid maid, friend;
    private final EmbeddedChannel connection;
    private final ItemStack bow = new ItemStack(Items.bow);
    private final List<Entity> owned = new ArrayList<>();
    private final List<int[]> placed = new ArrayList<>();
    private final List<EntityZombie> targets = new ArrayList<>();
    private final JsonArray observations = new JsonArray(), releases = new JsonArray();
    private final String run = UUID.randomUUID()
        .toString();
    private final int x, y = 160, z;
    private final long startedNanos = System.nanoTime();
    private Phase phase = Phase.SETTLE;
    private EntityZombie target;
    private EntityArrow hurtArrow;
    private float initialTargetHealth;
    private int ticks, phaseStart, baselineAmmo, baselineWear;
    private long lastWorldTick, phaseShots, phaseRejections, phaseSelections;
    private double startX, startZ;
    private String deferredFailure;
    private boolean finished, fmlRegistered, forgeRegistered, ownerAdded;
    private boolean approachVerified, movingVerified, blockedVerified, recoveryVerified, sitVerified, resumeVerified;
    private boolean dropObserved, leadObserved;

    private MaidBowRuntimeProbe(WorldServer world, boolean recordingEnabled) {
        this.world = world;
        this.recordingEnabled = recordingEnabled;
        var spawn = world.getSpawnPoint();
        x = (spawn.posX >> 4 << 4) + 8 + 64;
        z = (spawn.posZ >> 4 << 4) + 8 + 64;
        owner = new EntityPlayerMP(
            MinecraftServer.getServer(),
            world,
            new GameProfile(UUID.randomUUID(), "TLMNHBowRuntime"),
            new ItemInWorldManager(world));
        var manager = new NetworkManager(false);
        connection = new EmbeddedChannel(manager);
        try {
            manager.setConnectionState(EnumConnectionState.PLAY);
            IsolatedPlayerChannel.verify(manager, connection);
            new NetHandlerPlayServer(MinecraftServer.getServer(), manager, owner);
            maid = new EntityMaid(world);
            friend = new EntityMaid(world);
        } catch (RuntimeException error) {
            var errors = new ArrayList<String>();
            releaseChannel(errors);
            for (String cleanup : errors) error.addSuppressed(new IllegalStateException(cleanup));
            throw error;
        }
        owned.add(maid);
        owned.add(friend);
    }

    public static void start(WorldServer world) {
        start(world, true);
    }

    public static void start(WorldServer world, boolean recordingEnabled) {
        require(
            Boolean.getBoolean("tlmnh.devCommands") && !world.isRemote && world.provider.dimensionId == 0,
            "Dedicated test overworld required");
        require(pending == null, "Another natural bow experiment is active");
        require(
            world.difficultySetting.getDifficultyId() != 0,
            "Native zombie fixture requires non-peaceful difficulty");
        var probe = new MaidBowRuntimeProbe(world, recordingEnabled);
        pending = probe;
        try {
            FMLCommonHandler.instance()
                .bus()
                .register(probe);
            probe.fmlRegistered = true;
            MinecraftForge.EVENT_BUS.register(probe);
            probe.forgeRegistered = true;
            probe.begin();
        } catch (RuntimeException error) {
            probe.finish(false, error.toString());
        }
    }

    public static boolean isActive() {
        return pending != null && !pending.finished;
    }

    public static void cancel() {
        if (isActive()) pending.finish(false, "Test server is stopping");
    }

    private void begin() {
        // 原生实体更新要求周围区块已加载；不靠强制载入或仅生成一个区块掩盖停 tick
        for (int cx = (x - 36) >> 4; cx <= (x + 52) >> 4; cx++)
            for (int cz = (z - 40) >> 4; cz <= (z + 40) >> 4; cz++) require(
                world.getChunkProvider()
                    .chunkExists(cx, cz),
                "Fixture neighbourhood must already be loaded");
        var bounds = AxisAlignedBB.getBoundingBox(x - 4, y - 1, z - 8, x + 21, y + 6, z + 9);
        require(
            world.getEntitiesWithinAABB(Entity.class, bounds)
                .isEmpty(),
            "Fixture volume already has entities");
        for (int bx = x - 4; bx <= x + 20; bx++)
            for (int bz = z - 8; bz <= z + 8; bz++) for (int by = y - 1; by <= y + 5; by++)
                require(world.isAirBlock(bx, by, bz), "Fixture requires bounded empty air");
        for (int bx = x - 4; bx <= x + 20; bx++) for (int bz = z - 8; bz <= z + 8; bz++) {
            require(world.setBlock(bx, y - 1, bz, Blocks.stone, 0, 2), "Platform placement failed");
            placed.add(new int[] { bx, y - 1, bz });
        }
        owner.theItemInWorldManager.setGameType(GameType.CREATIVE);
        owner.setPosition(x + 2.5, y, z - 3.5);
        world.playerEntities.add(owner);
        ownerAdded = true;
        maid.bindTo(owner.getUniqueID());
        maid.setTask(MaidTask.RANGED_ATTACK);
        maid.setCurrentItemOrArmor(0, bow);
        maid.getMaidInventory()
            .setInventorySlotContents(0, new ItemStack(Items.arrow, 32));
        maid.setPosition(x + .5, y, z + .5);
        maid.getBrainCoordinator()
            .setSuspended(true);
        friend.bindTo(owner.getUniqueID());
        friend.setSitting(true);
        friend.getBrainCoordinator()
            .setSuspended(true);
        require(world.spawnEntityInWorld(maid), "Native maid spawn rejected");
        if (recordingEnabled) recorder = MaidEpisodeRecorder.open(maid, "natural_bow_runtime");
        startX = maid.posX;
        startZ = maid.posZ;
        lastWorldTick = world.getTotalWorldTime();
        TLMNotHard.LOG.info(
            "TLMNH_BOW_RUNTIME_STARTED run={} world={} owner=isolated-memory-player",
            run,
            world.provider.dimensionId);
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (finished) return;
        try {
            if (event.phase == TickEvent.Phase.START) {
                if (phase == Phase.MOVING && target != null && target.isEntityAlive()) {
                    // 确定的外部移动输入；碰撞、伤害和箭运动仍由原生世界更新
                    target.setPosition(x + 10.5, y, z - 2.5 + (ticks - phaseStart) * .08);
                    target.motionX = target.motionY = target.motionZ = 0;
                }
                return;
            }
            ticks++;
            require(
                ticks <= 700 && System.nanoTime() - startedNanos < 60_000_000_000L,
                "Total natural-tick deadline exceeded");
            require(ticks == 1 || world.getTotalWorldTime() > lastWorldTick, "World time did not advance naturally");
            lastWorldTick = world.getTotalWorldTime();
            require(deferredFailure == null, deferredFailure);
            require(
                world.getEntityByID(maid.getEntityId()) == maid && maid.isEntityAlive() && maid.isOwner(owner),
                "Maid identity lost");
            require(
                world.playerEntities.contains(owner) && owner.worldObj == world && owner.isEntityAlive(),
                "Fixture owner lost");
            IsolatedPlayerChannel.requireUnbound(connection);
            require(maid.ticksExisted >= ticks - 1, "Maid did not receive natural entity ticks");
            require(ticks - phaseStart <= phaseLimit(), "Natural phase timed out: " + phase);
            require(maid.posY >= y - .1 && Math.abs(maid.posZ - z - .5) < 7, "Actor left the fixture platform");
            require(
                ammo() == 32 - maid.getBrainCoordinator()
                    .getBowAttack()
                    .getShots() && bow.getItemDamage() == maid.getBrainCoordinator()
                        .getBowAttack()
                        .getShots(),
                "Natural release resources diverged");
            if (ticks % 5 == 0) record("sample");
            advance();
        } catch (RuntimeException error) {
            finish(false, error.toString());
        }
    }

    private int phaseLimit() {
        return phase == Phase.APPROACH ? 180 : phase == Phase.FRIEND ? 90 : phase == Phase.SITTING ? 35 : 110;
    }

    private void advance() {
        int elapsed = ticks - phaseStart;
        var attack = maid.getBrainCoordinator()
            .getBowAttack();
        var combat = maid.getBrainCoordinator()
            .getCombatController();
        switch (phase) {
            case SETTLE:
                if (elapsed < 8) return;
                newTarget(x + 14.5, z + .5);
                phaseSelections = combat.getSelections();
                maid.getBrainCoordinator()
                    .setSuspended(false);
                change(Phase.APPROACH);
                return;
            case APPROACH:
                if (!hit()) return;
                require(
                    combat.getSelections() > phaseSelections && combat.getPathRequests() > 0
                        && horizontalSq(maid.posX - startX, maid.posZ - startZ) >= 1
                        && dropObserved,
                    "Autonomous selection/approach/drop compensation not observed");
                approachVerified = true;
                record("native_static_hit_after_approach");
                newTarget(x + 10.5, z - 2.5);
                change(Phase.MOVING);
                return;
            case MOVING:
                if (!hit()) return;
                require(target.posZ > z - 2 && leadObserved, "Moving fixture or actual lead was not observed");
                movingVerified = true;
                record("native_lateral_hit");
                newTarget(maid.posX + 8, maid.posZ);
                friend.setPosition(maid.posX + 3, y, maid.posZ);
                require(world.spawnEntityInWorld(friend), "Friendly blocker spawn rejected");
                phaseShots = attack.getShots();
                phaseRejections = attack.getRejectedShots();
                baselineAmmo = ammo();
                baselineWear = bow.getItemDamage();
                change(Phase.FRIEND);
                return;
            case FRIEND:
                require(
                    attack.getShots() == phaseShots && ammo() == baselineAmmo && bow.getItemDamage() == baselineWear,
                    "Friendly blocker did not preserve release resources");
                if (elapsed < 60 || attack.getRejectedShots() <= phaseRejections) return;
                require(!hit(), "Blocked target was hit");
                blockedVerified = true;
                record("friendly_blocked_with_actual_attempt");
                friend.setPosition(maid.posX + 3, y, maid.posZ + 4);
                change(Phase.RECOVERY);
                return;
            case RECOVERY:
                if (!hit()) return;
                require(attack.getShots() > phaseShots, "Removing blocker did not restore native release");
                recoveryVerified = true;
                record("unblocked_target_hit");
                friend.setDead();
                newTarget(maid.posX + 8, maid.posZ);
                change(Phase.INTERRUPT_DRAW);
                return;
            case INTERRUPT_DRAW:
                if (attack.getDrawTicks() < 5 || attack.getDrawTicks() >= MaidBowAttack.DRAW_TICKS) return;
                clearArrows();
                phaseShots = attack.getShots();
                baselineAmmo = ammo();
                baselineWear = bow.getItemDamage();
                owner.setSneaking(true);
                require(owner.interactWith(maid) && maid.isSitting(), "Owner sit interaction rejected");
                change(Phase.SITTING);
                return;
            case SITTING:
                require(
                    maid.isSitting() && attack.getShots() == phaseShots
                        && ammo() == baselineAmmo
                        && bow.getItemDamage() == baselineWear,
                    "Sitting actor released or consumed resources");
                require(
                    attack.getDrawTicks() == 0 && !maid.hasPose(MaidPose.AIMING_BOW)
                        && !maid.hasPose(MaidPose.USING_ITEM),
                    "Natural hard stop retained drawing state");
                if (elapsed < 20) return;
                sitVerified = true;
                record("draw_interrupted_and_quiet");
                require(owner.interactWith(maid) && !maid.isSitting(), "Owner stand interaction rejected");
                owner.setSneaking(false);
                change(Phase.RESUME);
                return;
            case RESUME:
                if (!hit()) return;
                require(attack.getShots() > phaseShots, "Standing did not resume autonomous shooting");
                resumeVerified = true;
                record("standing_resumed_native_hit");
                finish(true, null);
        }
    }

    private void newTarget(double tx, double tz) {
        if (target != null) {
            if (recorder != null) recorder.finishVictim(target);
            target.setDead();
        }
        clearArrows();
        target = new EntityZombie(world);
        target.func_110163_bv();
        target.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
            .setBaseValue(0);
        target.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
            .setBaseValue(1);
        target.setCurrentItemOrArmor(4, new ItemStack(Items.diamond_helmet));
        target.setPosition(tx, y, tz);
        targets.add(target);
        owned.add(target);
        initialTargetHealth = target.getHealth();
        hurtArrow = null;
        require(world.spawnEntityInWorld(target), "Native target spawn rejected");
        if (recorder != null) recorder.watch(target);
    }

    private boolean hit() {
        return hurtArrow != null && target.getHealth() < initialTargetHealth;
    }

    private int ammo() {
        var stack = maid.getMaidInventory()
            .getStackInSlot(0);
        return stack == null ? 0 : stack.stackSize;
    }

    private void change(Phase next) {
        phase = next;
        phaseStart = ticks;
        record("phase_started");
    }

    private void clearArrows() {
        for (Entity entity : new ArrayList<>(owned)) if (entity instanceof EntityArrow) entity.setDead();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void joined(EntityJoinWorldEvent event) {
        if (finished || event.world != world || event.isCanceled()) return;
        if (event.entity instanceof EntityArrow arrow && arrow.shootingEntity == maid) {
            owned.add(arrow);
            var release = new JsonObject();
            release.addProperty("scope", "spawn event attempt; final successful releases use production shots counter");
            release.addProperty("phase", phase.name());
            release.addProperty("tick", ticks);
            release.addProperty(
                "arrow",
                arrow.getUniqueID()
                    .toString());
            release.addProperty("motionX", arrow.motionX);
            release.addProperty("motionY", arrow.motionY);
            release.addProperty("motionZ", arrow.motionZ);
            var solution = maid.getBrainCoordinator()
                .getBowAttack()
                .getLastSolution();
            if (solution != null) {
                release.addProperty("predictedZ", solution.predictedTarget.z);
                release.addProperty("targetZ", target == null ? null : target.posZ);
                if (phase == Phase.MOVING && target != null && solution.predictedTarget.z > target.posZ + .05)
                    leadObserved = true;
            }
            if (phase == Phase.APPROACH && target != null) {
                double dx = target.posX - arrow.posX, dz = target.posZ - arrow.posZ;
                double dy = target.boundingBox.minY + target.height * .5 - arrow.posY;
                if (arrow.motionY > 3 * dy / Math.sqrt(dx * dx + dy * dy + dz * dz) + .001) dropObserved = true;
            }
            releases.add(release);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void hurt(LivingHurtEvent event) {
        if (finished || event.isCanceled()) return;
        if (!(event.source.getSourceOfDamage() instanceof EntityArrow arrow) || arrow.shootingEntity != maid
            || !owned.contains(arrow)) {
            if (event.entityLiving == target)
                deferredFailure = "Target received damage outside the owned native arrow sample";
            return;
        }
        if (event.entityLiving == friend) deferredFailure = "Owned native arrow damaged the friendly blocker";
        if (event.entityLiving == target) hurtArrow = arrow;
    }

    @SubscribeEvent
    public void drops(LivingDropsEvent event) {
        if (!finished && targets.contains(event.entityLiving)) owned.addAll(event.drops);
    }

    private void record(String event) {
        var frame = new JsonObject();
        frame.addProperty("event", event);
        frame.addProperty("phase", phase.name());
        frame.addProperty("tick", ticks);
        frame.addProperty("worldTick", world.getTotalWorldTime());
        frame.addProperty("maidTicksExisted", maid.ticksExisted);
        frame.addProperty("maidX", maid.posX);
        frame.addProperty("maidZ", maid.posZ);
        frame.addProperty("targetX", target == null ? null : target.posX);
        frame.addProperty("targetZ", target == null ? null : target.posZ);
        frame.addProperty("targetHealth", target == null ? null : target.getHealth());
        frame.addProperty("arrowHitObserved", hurtArrow != null);
        var combat = maid.getBrainCoordinator()
            .getCombatController();
        frame.addProperty("combatTicks", combat.getTicks());
        frame.addProperty(
            "combatStatus",
            combat.getStatus()
                .name());
        frame.addProperty("scans", combat.getScans());
        frame.addProperty("selections", combat.getSelections());
        frame.addProperty("pathRequests", combat.getPathRequests());
        var attack = maid.getBrainCoordinator()
            .getBowAttack();
        frame.addProperty("shots", attack.getShots());
        frame.addProperty("rejectedShots", attack.getRejectedShots());
        frame.addProperty("drawTicks", attack.getDrawTicks());
        frame.addProperty(
            "bowStatus",
            attack.getStatus()
                .name());
        frame.addProperty("ammo", ammo());
        frame.addProperty("wear", bow.getItemDamage());
        observations.add(frame);
    }

    private void finish(boolean success, String error) {
        if (finished) return;
        finished = true;
        var cleanup = new ArrayList<String>();
        JsonObject episode = null;
        if (recorder != null) {
            try {
                episode = recorder.exportSummary();
                require(
                    episode.get("validObservation")
                        .getAsBoolean(),
                    "Episode observation failed");
                require(
                    episode.get("traceComplete")
                        .getAsBoolean(),
                    "Episode trace is incomplete");
                require(
                    episode.get("unboundArrowNativeRecords")
                        .getAsLong() == 0,
                    "Unbound arrow entered episode");
                require(
                    episode.getAsJsonObject("telemetry")
                        .getAsJsonObject("counters")
                        .get("RELEASE_CONFIRMED")
                        .getAsLong()
                        == maid.getBrainCoordinator()
                            .getBowAttack()
                            .getShots(),
                    "Recorded releases diverged from committed production releases");
                if (success) require(
                    episode.get("boundArrowNativeRecords")
                        .getAsLong() >= 4,
                    "Native ledger did not observe all four successful arrow phases");
            } catch (RuntimeException | LinkageError failure) {
                cleanup.add(
                    "episode:" + failure.getClass()
                        .getSimpleName());
            } finally {
                restore(recorder::close, cleanup);
            }
        }
        restore(
            () -> maid.getBrainCoordinator()
                .setSuspended(true),
            cleanup);
        for (Entity entity : new ArrayList<>(owned)) restore(() -> {
            entity.setDead();
            world.removeEntity(entity);
        }, cleanup);
        if (ownerAdded) restore(() -> {
            world.playerEntities.remove(owner);
            require(
                !world.playerEntities.contains(owner) && !world.loadedEntityList.contains(owner),
                "Fixture owner retained");
        }, cleanup);
        for (int[] at : placed) restore(() -> {
            require(
                world.getBlock(at[0], at[1], at[2]) == Blocks.stone && world.getBlockMetadata(at[0], at[1], at[2]) == 0,
                "Platform changed externally; refusing to overwrite");
            require(world.setBlockToAir(at[0], at[1], at[2]), "Platform removal failed");
        }, cleanup);
        releaseChannel(cleanup);
        if (fmlRegistered) restore(
            () -> FMLCommonHandler.instance()
                .bus()
                .unregister(this),
            cleanup);
        if (forgeRegistered) restore(() -> MinecraftForge.EVENT_BUS.unregister(this), cleanup);
        if (pending == this) pending = null;
        var report = new JsonObject();
        report.addProperty("run", run);
        report.addProperty("passed", success && cleanup.isEmpty());
        report.addProperty("error", error);
        report.addProperty("ticks", ticks);
        report.addProperty("recordingEnabled", recordingEnabled);
        report.addProperty(
            "committedShots",
            maid.getBrainCoordinator()
                .getBowAttack()
                .getShots());
        report.addProperty("remainingAmmo", ammo());
        report.addProperty("bowWear", bow.getItemDamage());
        if (episode != null) report.add("episode", episode);
        report.addProperty(
            "scope",
            "natural RC2 maid selection/navigation/bow/arrow ticks with scripted target inputs and isolated memory owner");
        report.addProperty(
            "targetFixture",
            "native zombie AI retained with zero movement speed, knockback resistance and a sun-protecting helmet");
        report.addProperty("approachVerified", approachVerified);
        report.addProperty("movingHitVerified", movingVerified);
        report.addProperty("dropCompensationObserved", dropObserved);
        report.addProperty("lateralLeadObserved", leadObserved);
        report.addProperty("friendlyBlockVerified", blockedVerified);
        report.addProperty("unblockRecoveryVerified", recoveryVerified);
        report.addProperty("sitInterruptVerified", sitVerified);
        report.addProperty("standResumeVerified", resumeVerified);
        report.addProperty("realPlayerNetworkVerified", false);
        report.addProperty("behaviourBenchmarkVerified", false);
        report.addProperty("renderingVerified", false);
        report.addProperty("cleanupErrors", String.join("; ", cleanup));
        report.add("observations", observations);
        report.add("spawnAttempts", releases);
        TLMNotHard.LOG.info("TLMNH_BOW_RUNTIME_{} {}", success && cleanup.isEmpty() ? "PASS" : "FAIL", report);
    }

    private static void restore(Runnable action, List<String> errors) {
        try {
            action.run();
        } catch (RuntimeException error) {
            errors.add(error.toString());
        }
    }

    private void releaseChannel(List<String> errors) {
        restore(() -> IsolatedPlayerChannel.requireUnbound(connection), errors);
        restore(() -> connection.finish(), errors);
        drain(true, errors);
        drain(false, errors);
    }

    private void drain(boolean inbound, List<String> errors) {
        while (true) {
            Object message;
            try {
                message = inbound ? connection.readInbound() : connection.readOutbound();
            } catch (RuntimeException error) {
                errors.add(error.toString());
                return;
            }
            if (message == null) return;
            final Object held = message;
            restore(() -> ReferenceCountUtil.release(held), errors);
        }
    }

    private static double horizontalSq(double dx, double dz) {
        return dx * dx + dz * dz;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
