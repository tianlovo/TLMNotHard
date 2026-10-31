package com.tianluoqaq.tlmnh.common.development;

import java.util.ArrayList;
import java.util.UUID;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.ai.MaidBoundedPathPlanner;
import com.tianluoqaq.tlmnh.common.ai.MaidBrainCoordinator;
import com.tianluoqaq.tlmnh.common.ai.MaidCombatController;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.PathResult;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.Status;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidDangerSensor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner.Owner;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime.InvalidationReason;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.life.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

/** 实际世界与导航适配检查；同步夹具不代替自然世界 tick 下的跟随和原版 benchmark */
public final class MaidBrainProbe {

    private MaidBrainProbe() {}

    public static void runActionControl(WorldServer world) {
        var spawn = world.getSpawnPoint();
        int x = (spawn.posX >> 4 << 4) + 8, z = (spawn.posZ >> 4 << 4) + 8, y = 180;
        require(
            world.getChunkProvider()
                .chunkExists(x >> 4, z >> 4),
            "control fixture chunk unloaded");
        for (int dx = -2; dx <= 7; dx++) for (int dz = -2; dz <= 4; dz++) for (int dy = -1; dy <= 3; dy++)
            require(world.isAirBlock(x + dx, y + dy, z + dz), "control fixture requires an empty bounded volume");
        long day = world.getWorldTime();
        var existingEntities = new java.util.HashSet<>(world.loadedEntityList);
        int players = world.playerEntities.size();
        var owner = new EntityPlayerMP(
            MinecraftServer.getServer(),
            world,
            new GameProfile(UUID.fromString("36ed6894-e390-405d-82ed-1228a4ce1bd7"), "[TLMNH-Control]"),
            new ItemInWorldManager(world));
        var maid = new EntityMaid(world);
        var target = new EntityZombie(world);
        var brain = maid.getBrainCoordinator();
        var runner = brain.getActionRunner();
        try {
            for (int dx = -2; dx <= 7; dx++) for (int dz = -2; dz <= 4; dz++)
                require(world.setBlock(x + dx, y - 1, z + dz, Blocks.stone, 0, 2), "control fixture floor");
            owner.setPosition(x + 6.5, y, z + 0.5);
            owner.theItemInWorldManager.setGameType(GameType.SURVIVAL);
            world.playerEntities.add(owner);
            maid.setPosition(x + 0.5, y, z + 0.5);
            maid.onGround = true;
            require(maid.bindTo(owner.getUniqueID()), "control fixture owner binding");
            maid.setSchedule(MaidSchedule.DAY);
            maid.setTask(MaidTask.ATTACK);
            maid.setCurrentItemOrArmor(0, new ItemStack(Items.diamond_sword));
            world.setWorldTime(0);
            awaitOwner(brain, Owner.FOLLOW, 25);
            require(
                maid.getNavigator()
                    .getPath() != null,
                "actual follow path installation");
            require(
                world.setBlock(x, y, z + 1, MaidLifeContent.maidBed, 0, 2)
                    && world.setBlock(x, y, z + 2, MaidLifeContent.maidBed, 8, 2),
                "control fixture paired bed");
            var bed = (TileEntityMaidBed) world.getTileEntity(x, y, z + 2);
            world.setWorldTime(16000);
            awaitOwner(brain, Owner.LIFE, 30);
            require(
                bed.isOwnedBy(maid) && maid.getNavigator()
                    .getPath() != null,
                "life control must own bed and path");
            var oldLife = runner.getCurrentRun();
            int phase = Math.floorMod(maid.getEntityId(), MaidCombatController.SCAN_INTERVAL) + 1;
            for (int i = 0; Math.floorMod(
                brain.getCombatController()
                    .getTicks() + 1
                    - phase,
                MaidCombatController.SCAN_INTERVAL) != 0 && i < 10; i++) brain.updateTask();
            require(runner.getOwner() == Owner.LIFE, "life must still own control immediately before combat scan");
            target.setPosition(x + 4.5, y, z + 0.5);
            require(world.spawnEntityInWorld(target), "control fixture target spawn");
            brain.updateTask();
            require(
                runner.getOwner() == Owner.COMBAT && oldLife.isCancelled()
                    && !bed.isOwnedBy(maid)
                    && (world.getBlockMetadata(x, y, z + 2) & 4) == 0,
                "combat must acquire control after life releases the bed");
            var combatPath = maid.getNavigator()
                .getPath();
            require(combatPath != null, "actual combat path installation");
            runner.release(oldLife);
            brain.getFollowController()
                .cancel();
            require(
                runner.getOwner() == Owner.COMBAT && maid.getNavigator()
                    .getPath() == combatPath,
                "old release and late follow cleanup must preserve combat control and path");
            target.setDead();
            world.setWorldTime(0);
            awaitOwner(brain, Owner.FOLLOW, 40);
            brain.setSuspended(true);
            require(
                runner.getOwner() == null && maid.getNavigator()
                    .noPath(),
                "suspension must release all action control");
        } finally {
            try {
                brain.resetTask();
            } finally {
                target.setDead();
                world.removeEntity(target);
                world.playerEntities.remove(owner);
                maid.setCurrentItemOrArmor(0, null);
                maid.setDead();
                owner.setDead();
                for (int dx = -2; dx <= 7; dx++) for (int dz = -2; dz <= 4; dz++)
                    for (int dy = -1; dy <= 3; dy++) world.setBlockToAir(x + dx, y + dy, z + dz);
                for (Object value : new ArrayList<>(world.loadedEntityList))
                    if (value instanceof EntityItem item && !existingEntities.contains(item)) {
                        item.setDead();
                        world.removeEntity(item);
                    }
                world.setWorldTime(day);
                require(world.playerEntities.size() == players, "control fixture owner cleanup");
            }
        }
        TLMNotHard.LOG.info(
            "TLMNH_ACTION_CONTROL_TEST_PASS order=follow,life,combat,follow bed=released paths=native stale_release=preserved late_follow_cancel=preserved suspend=cleared fixture=manual_root_dispatch natural_tick=unverified");
    }

    private static void awaitOwner(MaidBrainCoordinator brain, Owner owner, int limit) {
        for (int i = 0; i < limit; i++) {
            brain.updateTask();
            if (brain.getActionRunner()
                .getOwner() == owner) return;
        }
        throw new IllegalStateException("Control owner not reached: " + owner);
    }

    public static void runDangerSensor(WorldServer world) {
        var spawn = world.getSpawnPoint();
        int x = (spawn.posX >> 4 << 4) + 8, z = (spawn.posZ >> 4 << 4) + 8, y = 200;
        require(
            world.getChunkProvider()
                .chunkExists(x >> 4, z >> 4),
            "danger fixture chunk unloaded");
        for (int dy = 0; dy < 3; dy++) for (int dz = -1; dz <= 1; dz++)
            require(world.isAirBlock(x + 2, y + dy, z + dz), "danger fixture wall must replace only air");
        var maid = new EntityMaid(world);
        maid.setPosition(x + 0.5, y, z + 0.5);
        var brain = maid.getBrainCoordinator();
        var sensor = new MaidDangerSensor(maid, brain.getTelemetry());
        var mobs = new ArrayList<EntityZombie>();
        try {
            for (int dy = 0; dy < 3; dy++) for (int dz = -1; dz <= 1; dz++)
                require(world.setBlock(x + 2, y + dy, z + dz, Blocks.stone, 0, 2), "danger fixture wall placement");
            for (int i = 0; i < 33; i++) {
                var mob = new EntityZombie(world);
                mobs.add(mob);
                mob.setPosition(x + 4.5, y, z + 0.5);
                require(world.spawnEntityInWorld(mob), "danger fixture mob spawn rejected");
            }
            var query = world.getEntitiesWithinAABB(IMob.class, maid.boundingBox.expand(8, 4, 8));
            require(query.size() == 33, "danger fixture must have exactly 33 native candidates");
            for (Object value : query) require(mobs.contains(value), "unexpected native candidate in danger fixture");
            var visible = (EntityZombie) query.get(32);
            visible.setPosition(x - 3.5, y, z + 0.5);
            sensor.beginFrame(
                brain.getPolicyRuntime()
                    .getContext(),
                1);
            require(!sensor.hasKnownDanger(), "first bounded batch is hidden by the actual wall");
            sensor.beginFrame(
                brain.getPolicyRuntime()
                    .getContext(),
                11);
            require(sensor.hasKnownDanger(), "next bounded batch must find candidate 33");
            visible.setDead();
            require(!sensor.hasKnownDanger(), "dead threat must be discarded without waiting for the next scan");
        } finally {
            sensor.clear();
            brain.resetTask();
            for (var mob : mobs) {
                mob.setDead();
                world.removeEntity(mob);
            }
            for (int dy = 0; dy < 3; dy++) for (int dz = -1; dz <= 1; dz++) {
                world.setBlockToAir(x + 2, y + dy, z + dz);
                require(world.isAirBlock(x + 2, y + dy, z + dz), "danger fixture wall cleanup");
            }
            maid.setDead();
        }
        TLMNotHard.LOG.info(
            "TLMNH_DANGER_SENSOR_TEST_PASS candidates=33 first_batch=32_hidden next_batch=visible_33 dead=discarded wall=restored fixture=manual_native_adapter natural_tick=unverified");
    }

    public static void runOwnerSensor(WorldServer world) {
        var owner = new EntityPlayerMP(
            MinecraftServer.getServer(),
            world,
            new GameProfile(UUID.fromString("85515bbe-7adb-4193-b171-4b2978403a3b"), "[TLMNH-Owner]"),
            new ItemInWorldManager(world));
        owner.theItemInWorldManager.setGameType(GameType.SURVIVAL);
        var maid = new EntityMaid(world);
        require(maid.bindTo(owner.getUniqueID()), "owner sensor binding");
        maid.setSitting(true);
        var brain = maid.getBrainCoordinator();
        var sensor = brain.getOwnerSensor();
        int originalPlayers = world.playerEntities.size();
        world.playerEntities.add(owner);
        try {
            brain.updateTask();
            long before = sensor.getScans();
            require(sensor.findOwner() == owner && sensor.findOwner() == owner, "shared positive owner observation");
            require(sensor.getScans() == before + 1, "only one query per decision frame");
            world.playerEntities.remove(owner);
            require(sensor.findOwner() == null, "disconnected reference must be rejected immediately");
            world.playerEntities.add(owner);
            require(
                sensor.findOwner() == null && sensor.getScans() == before + 1,
                "negative result stays in its frame");
            brain.updateTask();
            require(sensor.findOwner() == owner, "fresh frame must resolve the restored owner");
            owner.theItemInWorldManager.setGameType(GameType.getByID(3));
            require(sensor.findOwner() == null, "spectator owner must be rejected immediately");
            brain.updateTask();
            require(sensor.findOwner() == null, "spectator must not enter a new observation");
            owner.theItemInWorldManager.setGameType(GameType.SURVIVAL);
            brain.updateTask();
            require(sensor.findOwner() == owner, "new frame after spectator exit");
            brain.getPolicyRuntime()
                .invalidate(InvalidationReason.RESET);
            require(sensor.findOwner() == null, "invalidated policy must discard its observation");
            brain.updateTask();
            require(sensor.findOwner() == owner, "new policy lease must reobserve the owner");
            TLMNotHard.LOG.info(
                "TLMNH_OWNER_SENSOR_TEST_PASS queries={} shared=once_per_frame offline=immediate spectator=immediate negative=frame_scoped token=discarded next_frame=refreshed fixture=synthetic_owner natural_tick=unverified",
                sensor.getScans() - before);
        } finally {
            brain.resetTask();
            world.playerEntities.remove(owner);
            maid.setDead();
            owner.setDead();
            require(world.playerEntities.size() == originalPlayers, "owner sensor fixture cleanup");
        }
    }

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
