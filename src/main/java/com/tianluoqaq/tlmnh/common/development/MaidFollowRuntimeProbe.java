package com.tianluoqaq.tlmnh.common.development;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.Status;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** 显式开发命令启动的真实服务端 tick 实验，不代替网络点击或原版行为 benchmark */
public final class MaidFollowRuntimeProbe {

    private static final int TOTAL_TICK_LIMIT = 240;
    private static final long WALL_NANOS_LIMIT = 30_000_000_000L;
    private static MaidFollowRuntimeProbe pending;

    private enum Phase {
        WARMUP,
        FOLLOW,
        SIT_SETTLE,
        SIT_CHECK,
        CARRY,
        RELEASE
    }

    private final EntityPlayerMP player;
    private final EntityMaid maid;
    private final WorldServer world;
    private final String originalModel, runId = UUID.randomUUID()
        .toString();
    private final int originalSlot;
    private final ItemStack originalStack;
    private final boolean originalSneaking;
    private final long startedNanos = System.nanoTime();
    private final JsonArray observations = new JsonArray();
    private Phase phase = Phase.WARMUP;
    private int ticks, phaseStart;
    private double startX, startZ, startDistanceSq, followDistanceSq, followMovementSq, sitX, sitZ;
    private long initialRequests, seatedRequests, carriedRequests;
    private boolean finished, registered, loggedOut, followVerified, sitVerified, carryVerified, releaseVerified;

    private MaidFollowRuntimeProbe(EntityPlayerMP player, EntityMaid maid) {
        this.player = player;
        this.maid = maid;
        world = (WorldServer) player.worldObj;
        originalModel = maid.getModelId();
        originalSlot = player.inventory.currentItem;
        originalStack = copy(player.inventory.getStackInSlot(originalSlot));
        originalSneaking = player.isSneaking();
    }

    public static void start(EntityPlayerMP player) {
        require(Boolean.getBoolean("tlmnh.devCommands"), "Development commands are disabled");
        require(pending == null, "Another follow runtime experiment is active");
        require(online(player), "A real connected server player is required");
        require(
            player.inventory.currentItem >= 0 && player.inventory.currentItem < 9,
            "Select a main-hand hotbar slot");
        require(player.inventory.getItemStack() == null, "Empty cursor required");
        require(player.openContainer == player.inventoryContainer, "Close the current container before testing");
        require(
            player.ridingEntity == null && player.riddenByEntity == null,
            "Player must not be mounted or carrying another entity");
        var maid = AnimationLab.ownedSceneMaid(player);
        require(maid.ridingEntity == null && maid.riddenByEntity == null, "Lab maid must not be mounted");
        var probe = new MaidFollowRuntimeProbe(player, maid);
        pending = probe;
        try {
            FMLCommonHandler.instance()
                .bus()
                .register(probe);
            probe.registered = true;
            probe.begin();
        } catch (RuntimeException error) {
            probe.finish(false, error.toString());
        }
    }

    /** AnimationLab 的旁观保护仅为当前已授权实验玩家暂时允许 creative */
    public static boolean isActivePlayer(EntityPlayerMP player) {
        return pending != null && !pending.finished && pending.player == player && player.worldObj == pending.world;
    }

    private void begin() {
        maid.getBrainCoordinator()
            .setSuspended(true);
        maid.setSitting(true);
        maid.getNavigator()
            .clearPathEntity();
        maid.setAttackTarget(null);
        maid.setPose(MaidPose.SLEEPING, false);
        maid.setLocationAndAngles(0.5, 65, 0.5, 0, 0);
        maid.motionX = maid.motionY = maid.motionZ = 0;
        maid.onGround = true;
        player.inventory.setInventorySlotContents(originalSlot, null);
        player.setSneaking(false);
        player.setGameType(GameType.CREATIVE);
        protectPlayer(false);
        player.motionX = player.motionY = player.motionZ = 0;
        player.fallDistance = 0;
        player.setPositionAndUpdate(6.5, 65, 0.5);
        player.inventoryContainer.detectAndSendChanges();
        startX = maid.posX;
        startZ = maid.posZ;
        startDistanceSq = maid.getDistanceSqToEntity(player);
        record("started");
        TLMNotHard.LOG.info(
            "TLMNH_FOLLOW_RUNTIME_STARTED run={} player={} maid={}",
            runId,
            player.getCommandSenderName(),
            maid.getUniqueID());
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) return;
        try {
            ticks++;
            require(
                ticks <= TOTAL_TICK_LIMIT && System.nanoTime() - startedNanos <= WALL_NANOS_LIMIT,
                "Experiment exceeded its total deadline");
            verifyParticipants();
            require(ticks - phaseStart <= phaseLimit(), "Phase timed out: " + phase);
            if (ticks % 5 == 0) record("sample");
            advance();
        } catch (RuntimeException error) {
            finish(false, error.toString());
        }
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player == player && !finished) {
            loggedOut = true;
            finish(false, "Player logged out during experiment");
        }
    }

    private void verifyParticipants() {
        require(online(player) && player.isEntityAlive(), "Player disconnected or died");
        require(player.worldObj == world && maid.worldObj == world && AnimationLab.isLab(world), "World changed");
        require(
            maid.isEntityAlive() && world.getEntityByID(maid.getEntityId()) == maid && maid.isOwner(player),
            "Lab actor disappeared or ownership changed");
        require(
            player.theItemInWorldManager.getGameType()
                .getID() == 1,
            "Player left the temporary creative mode");
        require(
            player.capabilities.disableDamage && player.capabilities.allowFlying && player.capabilities.isFlying,
            "Temporary observer abilities became unsafe");
        require(
            player.inventory.currentItem == originalSlot && player.inventory.getItemStack() == null,
            "Player changed selected slot or cursor contents");
        require(player.openContainer == player.inventoryContainer, "A container opened during experiment");
        require(
            horizontalSq(player.posX, player.posZ, 6.5, 0.5) < 0.25,
            "Player moved away from the fixed owner position");
        require(
            Double.isFinite(maid.posX) && Double.isFinite(maid.posY) && Double.isFinite(maid.posZ),
            "Actor position became non-finite");
    }

    private int phaseLimit() {
        switch (phase) {
            case FOLLOW:
                return 100;
            case SIT_SETTLE:
            case SIT_CHECK:
                return 30;
            default:
                return 20;
        }
    }

    private void advance() {
        int elapsed = ticks - phaseStart;
        var follow = maid.getBrainCoordinator()
            .getFollowController();
        switch (phase) {
            case WARMUP:
                if (elapsed < 5) return;
                startX = maid.posX;
                startZ = maid.posZ;
                startDistanceSq = maid.getDistanceSqToEntity(player);
                initialRequests = follow.getPathRequests();
                require(startDistanceSq >= 25, "Follow fixture did not start outside the follow threshold");
                maid.setSitting(false);
                maid.getBrainCoordinator()
                    .setSuspended(false);
                changePhase(Phase.FOLLOW);
                return;
            case FOLLOW:
                double distance = maid.getDistanceSqToEntity(player);
                if (distance > 10.5625 || horizontalSq(maid.posX, maid.posZ, startX, startZ) < 1) return;
                require(
                    distance < startDistanceSq && follow.getPathRequests() > initialRequests,
                    "Follow movement did not consume a real navigation request");
                followVerified = true;
                followDistanceSq = distance;
                followMovementSq = horizontalSq(maid.posX, maid.posZ, startX, startZ);
                record("follow_verified");
                setHand(null);
                player.setSneaking(true);
                require(player.interactWith(maid) && maid.isSitting(), "Sneak empty-hand sit interaction was rejected");
                changePhase(Phase.SIT_SETTLE);
                return;
            case SIT_SETTLE:
                require(
                    maid.isSitting() && maid.getNavigator()
                        .noPath(),
                    "Sit failed to clear navigation");
                if (elapsed < 8) return;
                sitX = maid.posX;
                sitZ = maid.posZ;
                seatedRequests = follow.getPathRequests();
                changePhase(Phase.SIT_CHECK);
                return;
            case SIT_CHECK:
                require(
                    maid.isSitting() && maid.getNavigator()
                        .noPath() && follow.getStatus() == Status.SITTING,
                    "Sit constraint was not retained in natural server ticks");
                require(
                    horizontalSq(maid.posX, maid.posZ, sitX, sitZ) <= 0.0025,
                    "Seated actor kept moving after settling");
                require(follow.getPathRequests() == seatedRequests, "Seated actor requested another path");
                if (elapsed < 12) return;
                sitVerified = true;
                record("sit_verified");
                player.setSneaking(false);
                setHand(new ItemStack(Items.saddle));
                require(
                    player.interactWith(maid) && maid.ridingEntity == player && player.riddenByEntity == maid,
                    "Saddle carry interaction did not create both riding links");
                carriedRequests = follow.getPathRequests();
                changePhase(Phase.CARRY);
                return;
            case CARRY:
                require(
                    maid.ridingEntity == player && player.riddenByEntity == maid,
                    "Riding links changed during carry");
                require(
                    maid.getNavigator()
                        .noPath() && follow.getPathRequests() == carriedRequests,
                    "Carried actor navigated or requested another path");
                require(follow.getStatus() == Status.RIDING, "Brain did not observe riding in natural ticks");
                if (elapsed < 12) return;
                carryVerified = true;
                record("carry_verified");
                require(
                    player.interactWith(maid) && maid.ridingEntity == null && player.riddenByEntity == null,
                    "Second saddle interaction did not release both riding links");
                changePhase(Phase.RELEASE);
                return;
            case RELEASE:
                require(maid.ridingEntity == null && player.riddenByEntity == null, "Actor remounted after release");
                if (elapsed < 3) return;
                releaseVerified = true;
                record("release_verified");
                finish(true, null);
        }
    }

    private void changePhase(Phase next) {
        phase = next;
        phaseStart = ticks;
        record("phase_started");
    }

    private void setHand(ItemStack stack) {
        player.inventory.setInventorySlotContents(originalSlot, stack);
        player.inventoryContainer.detectAndSendChanges();
    }

    private void record(String event) {
        var frame = new JsonObject();
        frame.addProperty("event", event);
        frame.addProperty("phase", phase.name());
        frame.addProperty("tick", ticks);
        frame.addProperty("phaseTick", ticks - phaseStart);
        frame.addProperty("maidX", maid.posX);
        frame.addProperty("maidY", maid.posY);
        frame.addProperty("maidZ", maid.posZ);
        frame.addProperty("ownerX", player.posX);
        frame.addProperty("ownerY", player.posY);
        frame.addProperty("ownerZ", player.posZ);
        frame.addProperty("distanceSq", maid.getDistanceSqToEntity(player));
        frame.addProperty("horizontalMovementSq", horizontalSq(maid.posX, maid.posZ, startX, startZ));
        frame.addProperty(
            "brainStatus",
            maid.getBrainCoordinator()
                .getFollowController()
                .getStatus()
                .name());
        frame.addProperty(
            "brainTicks",
            maid.getBrainCoordinator()
                .getFollowController()
                .getTicks());
        frame.addProperty(
            "pathRequests",
            maid.getBrainCoordinator()
                .getFollowController()
                .getPathRequests());
        frame.addProperty(
            "pathFailures",
            maid.getBrainCoordinator()
                .getFollowController()
                .getPathFailures());
        frame.addProperty(
            "noPath",
            maid.getNavigator()
                .noPath());
        frame.addProperty("sitting", maid.isSitting());
        frame.addProperty("ridingPlayer", maid.ridingEntity == player);
        frame.addProperty("playerCarriesMaid", player.riddenByEntity == maid);
        observations.add(frame);
    }

    private void finish(boolean passed, String error) {
        if (finished) return;
        finished = true;
        double finalDistanceSq = maid.getDistanceSqToEntity(player);
        double movementSq = horizontalSq(maid.posX, maid.posZ, startX, startZ);
        long requests = maid.getBrainCoordinator()
            .getFollowController()
            .getPathRequests();
        boolean restored = false;
        var cleanupErrors = new ArrayList<String>();
        try {
            restore(() -> {
                maid.getBrainCoordinator()
                    .setSuspended(true);
                maid.mountEntity(null);
                if (player.riddenByEntity == maid) player.riddenByEntity = null;
                maid.setSitting(true);
                maid.setModelId(originalModel);
                maid.setLocationAndAngles(0.5, 65, 0.5, 180, 0);
                maid.prevRotationYaw = maid.renderYawOffset = maid.prevRenderYawOffset = 180;
                maid.rotationYawHead = maid.prevRotationYawHead = 180;
                maid.motionX = maid.motionY = maid.motionZ = 0;
            }, cleanupErrors);
            restore(() -> {
                player.inventory.setInventorySlotContents(originalSlot, copy(originalStack));
                player.inventory.currentItem = originalSlot;
                player.setSneaking(originalSneaking);
                player.inventoryContainer.detectAndSendChanges();
            }, cleanupErrors);
            restore(() -> player.setGameType(GameType.getByID(3)), cleanupErrors);
            restore(() -> protectPlayer(true), cleanupErrors);
            if (!loggedOut && online(player)) {
                restore(() -> {
                    AnimationLab.enter(player, originalModel);
                    AnimationLab.verifyObserver(player);
                }, cleanupErrors);
                restored = cleanupErrors.isEmpty() && player.theItemInWorldManager.getGameType()
                    .getID() == 3;
            } else {
                // 注销事件发生在 vanilla 移除玩家之前，不能在该事件中重新传送或加入玩家
                cleanupErrors.add("Player offline; lab entry deferred to next login");
            }
        } finally {
            try {
                if (registered) restore(
                    () -> FMLCommonHandler.instance()
                        .bus()
                        .unregister(this),
                    cleanupErrors);
            } finally {
                if (pending == this) pending = null;
            }
        }
        String restoreError = cleanupErrors.isEmpty() ? null : String.join("; ", cleanupErrors);
        var result = new JsonObject();
        result.addProperty("run", runId);
        result.addProperty(
            "scope",
            "natural server ticks and server-side EntityPlayer.interactWith; no C02 packet or visual equivalence validation");
        result.addProperty("passed", passed && restored && restoreError == null);
        result.addProperty("ticks", ticks);
        result.addProperty("wallMillis", (System.nanoTime() - startedNanos) / 1_000_000L);
        result.addProperty("player", player.getCommandSenderName());
        result.addProperty(
            "maid",
            maid.getUniqueID()
                .toString());
        result.addProperty("originalModel", originalModel);
        result.addProperty("initialDistanceSq", startDistanceSq);
        result.addProperty("followEndDistanceSq", followDistanceSq);
        result.addProperty("followHorizontalMovementSq", followMovementSq);
        finiteNumber(result, "finalDistanceSq", finalDistanceSq);
        finiteNumber(result, "horizontalMovementSq", movementSq);
        result.addProperty("pathRequests", requests);
        result.addProperty("newPathRequests", requests - initialRequests);
        result.addProperty("followVerified", followVerified);
        result.addProperty("sitVerified", sitVerified);
        result.addProperty("carryVerified", carryVerified);
        result.addProperty("releaseVerified", releaseVerified);
        result.addProperty("cleanupRestored", restored);
        result.addProperty("error", error);
        result.addProperty("restoreError", restoreError);
        result.add("observations", observations);
        if (passed && restored && restoreError == null)
            TLMNotHard.LOG.info("TLMNH_FOLLOW_RUNTIME_TEST_PASS {}", result);
        else TLMNotHard.LOG.error("TLMNH_FOLLOW_RUNTIME_TEST_FAIL {}", result);
    }

    private void protectPlayer(boolean noClip) {
        player.capabilities.disableDamage = true;
        player.capabilities.allowFlying = true;
        player.capabilities.isFlying = true;
        player.noClip = noClip;
        player.fallDistance = 0;
        player.sendPlayerAbilities();
    }

    private static boolean online(EntityPlayerMP player) {
        return player != null && player.playerNetServerHandler != null
            && player.playerNetServerHandler.netManager.isChannelOpen()
            && MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList.contains(player);
    }

    private static ItemStack copy(ItemStack stack) {
        return stack == null ? null : stack.copy();
    }

    private static void restore(Runnable action, List<String> errors) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            errors.add(failure.toString());
        }
    }

    private static void finiteNumber(JsonObject result, String name, double value) {
        if (Double.isFinite(value)) result.addProperty(name, value);
        else result.addProperty(name, "non-finite");
    }

    private static double horizontalSq(double x, double z, double otherX, double otherZ) {
        return (x - otherX) * (x - otherX) + (z - otherZ) * (z - otherZ);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
