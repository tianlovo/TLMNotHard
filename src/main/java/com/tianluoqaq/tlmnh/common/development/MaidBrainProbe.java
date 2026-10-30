package com.tianluoqaq.tlmnh.common.development;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.WorldServer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.ai.MaidBoundedPathPlanner;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.PathResult;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.Status;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

/** 实际世界与导航适配检查；同步夹具不代替自然世界 tick 下的跟随和原版 benchmark */
public final class MaidBrainProbe {

    private MaidBrainProbe() {}

    public static void run(WorldServer world) {
        var spawn = world.getSpawnPoint();
        require(
            world.getChunkProvider()
                .chunkExists(spawn.posX >> 4, spawn.posZ >> 4),
            "fixture chunk unloaded");
        var owner = new EntityPlayerMP(
            MinecraftServer.getServer(),
            world,
            new GameProfile(UUID.fromString("f1d5d61a-95b2-486d-b1fb-55b0a09cd879"), "[TLMNH-Brain]"),
            new ItemInWorldManager(world));
        var maid = new EntityMaid(world);
        double y = world.getTopSolidOrLiquidBlock(spawn.posX, spawn.posZ);
        owner.setPosition(spawn.posX + 6.5D, y, spawn.posZ + 0.5D);
        maid.setPosition(spawn.posX + 0.5D, y, spawn.posZ + 0.5D);
        maid.onGround = true;
        require(maid.bindTo(owner.getUniqueID()), "owner binding");
        var brain = maid.getBrainCoordinator();
        var follow = brain.getFollowController();
        world.playerEntities.add(owner);
        try {
            for (int i = 0; i < 12; i++) brain.updateTask();
            require(follow.getOwnerScans() >= 1 && follow.getPathRequests() >= 1, "owner sensing and bounded request");
            var navigation = follow.getStatus();
            require(
                navigation == Status.FOLLOWING || navigation == Status.PATH_COOLDOWN
                    || navigation == Status.PATH_UNAVAILABLE
                    || navigation == Status.DESTINATION_UNLOADED,
                "navigation result");
            var attempt = brain.getLastPathAttempt();
            require(attempt != null && attempt.options.supported, "native adapter unavailable");
            require(attempt.searchRange == Math.min(attempt.nativeRange, 16.0F), "bounded actual search range");
            require(attempt.options.canSwim && Boolean.TRUE.equals(attempt.navigable), "actual grounded native gate");
            maid.setSitting(true);
            brain.updateTask();
            require(
                follow.getStatus() == Status.SITTING && maid.getNavigator()
                    .noPath(),
                "sit cancellation");
            maid.setSitting(false);
            maid.mountEntity(owner);
            brain.updateTask();
            require(
                follow.getStatus() == Status.RIDING && maid.getNavigator()
                    .noPath(),
                "carry cancellation");
            maid.mountEntity(null);
            maid.setPose(MaidPose.SLEEPING, true);
            brain.updateTask();
            require(
                follow.getStatus() == Status.SLEEPING && maid.getNavigator()
                    .noPath(),
                "sleep cancellation");
            maid.setPose(MaidPose.SLEEPING, false);
            maid.setAttackTarget(owner);
            maid.setPose(MaidPose.USING_ITEM, true);
            maid.setPose(MaidPose.AIMING_BOW, true);
            brain.updateTask();
            require(follow.getStatus() == Status.BUSY, "busy yields");
            brain.resetTask();
            require(
                maid.getAttackTarget() == owner && maid.hasPose(MaidPose.USING_ITEM)
                    && maid.hasPose(MaidPose.AIMING_BOW),
                "unowned combat and item states preserved");
            brain.setSuspended(true);
            brain.updateTask();
            require(follow.getStatus() == Status.SUSPENDED && brain.isSuspended(), "explicit suspension");
            brain.setSuspended(false);
            require(!brain.isSuspended(), "suspension release");
            verifyNativeNavigation(maid, owner, attempt);
            TLMNotHard.LOG.info(
                "TLMNH_BRAIN_ADAPTER_TEST_PASS owner=checked stop=sit,carry,sleep,busy,suspend resources=preserved navigation={} requests={} native_flags=checked native_gate=checked native_range={} bounded_range={} actual_movement=unverified benchmark=unverified",
                navigation,
                follow.getPathRequests(),
                attempt.nativeRange,
                attempt.searchRange);
        } finally {
            brain.resetTask();
            maid.mountEntity(null);
            world.playerEntities.remove(owner);
            maid.setDead();
            owner.setDead();
        }
    }

    private static void verifyNativeNavigation(EntityMaid maid, EntityPlayerMP owner,
        MaidBoundedPathPlanner.Attempt original) {
        var navigator = maid.getNavigator();
        var planner = new MaidBoundedPathPlanner();
        try {
            navigator.setCanSwim(false);
            navigator.setEnterDoors(false);
            navigator.setBreakDoors(true);
            navigator.setAvoidsWater(true);
            maid.onGround = false;
            require(
                planner.request(maid, owner.posX, owner.boundingBox.minY, owner.posZ, 1.0D) == PathResult.NOT_NAVIGABLE,
                "native airborne gate must refuse navigation");
            var grounded = planner.getLastAttempt();
            require(
                grounded.options.supported && !grounded.options.canSwim
                    && !grounded.options.enterDoors
                    && grounded.breakDoors
                    && grounded.avoidWater
                    && Boolean.FALSE.equals(grounded.navigable),
                "native flags must be reread after setters");
            maid.onGround = true;
            planner.request(maid, owner.posX, owner.boundingBox.minY, owner.posZ, 1.0D);
            require(
                Boolean.TRUE.equals(planner.getLastAttempt().navigable),
                "native grounded gate must allow planning");
            require(navigator.getPathSearchRange() == original.nativeRange, "follow range must remain unchanged");
        } finally {
            navigator.clearPathEntity();
            navigator.setCanSwim(original.options.canSwim);
            navigator.setEnterDoors(original.options.enterDoors);
            navigator.setBreakDoors(original.breakDoors);
            navigator.setAvoidsWater(original.avoidWater);
            maid.onGround = true;
        }
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new IllegalStateException("TLMNH brain adapter probe failed: " + name);
    }
}
