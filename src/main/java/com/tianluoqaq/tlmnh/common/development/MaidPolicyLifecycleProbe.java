package com.tianluoqaq.tlmnh.common.development;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.ai.MaidBrainLifecycle;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime.SelectionResult;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime.TickResult;

/** 实际实体/NBT/登记边界夹具，不是自然行为或F/N智能程度测评 */
public final class MaidPolicyLifecycleProbe {

    private final WorldServer world;
    private final List<EntityMaid> actors = new ArrayList<>();
    private final JsonArray cases = new JsonArray();

    private MaidPolicyLifecycleProbe(WorldServer world) {
        this.world = world;
    }

    public static void run(WorldServer world) {
        if (world == null || world.isRemote) throw new IllegalArgumentException("Server world required");
        var probe = new MaidPolicyLifecycleProbe(world);
        var report = new JsonObject();
        report.addProperty("schema", "tlmnh-policy-lifecycle-v1");
        report.addProperty("scope", "native entity/NBT/registration adapters and unavailable profile refusal");
        report.addProperty("naturalBehaviourVerified", false);
        report.addProperty("intelligenceBenchmarkVerified", false);
        Throwable failure = null;
        try {
            probe.outerReadIdentity();
            probe.directChildRead();
            probe.unavailableProfiles();
            probe.unloadAndRegister();
            probe.loadedListIdentity();
            probe.failedReadRemainsBlocked();
            probe.chunkFailureIsolation();
        } catch (RuntimeException | Error problem) {
            failure = problem;
        } finally {
            try {
                probe.cleanup();
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
            }
        }
        report.add("cases", probe.cases);
        report.addProperty("cleanup", "native_removeEntity_scheduled");
        boolean allDead = true;
        for (var maid : probe.actors) allDead &= maid.isDead;
        report.addProperty("allActorsDead", allDead);
        report.addProperty("passed", failure == null);
        if (failure == null) {
            TLMNotHard.LOG.info("TLMNH_POLICY_LIFECYCLE_PASS {}", report);
            return;
        }
        report.addProperty(
            "error",
            failure.getClass()
                .getSimpleName());
        TLMNotHard.LOG.error("TLMNH_POLICY_LIFECYCLE_FAIL {}", report, failure);
        if (failure instanceof RuntimeException problem) throw problem;
        throw (Error) failure;
    }

    private void outerReadIdentity() {
        var maid = remember(new ReadObservingMaid(world));
        var brain = maid.getBrainCoordinator();
        require(brain.peekPolicyRuntime() == null, "readonly peek created a runtime");
        var old = brain.getPolicyRuntime();
        var oldToken = old.getContext()
            .getToken();
        var saved = save(maid);
        var identity = UUID.randomUUID();
        saved.setLong("UUIDMost", identity.getMostSignificantBits());
        saved.setLong("UUIDLeast", identity.getLeastSignificantBits());
        saved.removeTag("PersistentIDMSB");
        saved.removeTag("PersistentIDLSB");
        maid.expectedRuntime = old;
        maid.expectedToken = oldToken;
        maid.readFromNBT(saved);
        require(maid.observedRetiredBeforeChild, "outer read did not retire before its child callback");
        require(identity.equals(maid.getUniqueID()), "native UUID restore");
        require(
            old.isRetired() && !old.getContext()
                .isCurrent(oldToken),
            "old reference alive after NBT return");
        require(
            old.tick(() -> { throw new IllegalStateException("Retired policy queried life"); }) == TickResult.RETIRED,
            "old reference dispatched after read before getter");
        require(brain.peekPolicyRuntime() == null, "successful read eagerly constructed a new runtime");
        var current = brain.getPolicyRuntime();
        require(
            current != old && current.isReady()
                && identity.equals(
                    current.getContext()
                        .getMaidId()),
            "runtime did not bind restored native UUID");
        require(
            !current.getContext()
                .isCurrent(oldToken),
            "old token accepted by replacement");
        passed("outer_native_nbt_identity", "old token retired before native child and before next runtime getter");
    }

    private void directChildRead() {
        var maid = remember(new EntityMaid(world));
        var brain = maid.getBrainCoordinator();
        var old = brain.getPolicyRuntime();
        var token = old.getContext()
            .getToken();
        maid.readEntityFromNBT(save(maid));
        require(
            old.isRetired() && !old.getContext()
                .isCurrent(token),
            "direct child read retained old token");
        require(brain.peekPolicyRuntime() == null, "direct child read eagerly created runtime");
        require(
            brain.getPolicyRuntime() != old && brain.getPolicyRuntime()
                .isReady(),
            "direct child fresh runtime");
        passed("direct_native_child_read", "child-only callers also retire transients and permit a fresh runtime");
    }

    private void unavailableProfiles() {
        var maid = remember(new EntityMaid(world));
        var brain = maid.getBrainCoordinator();
        var runtime = brain.getPolicyRuntime();
        for (var profile : new MaidBrainProfile[] { MaidBrainProfile.FAITHFUL_TLM, MaidBrainProfile.ENHANCED }) {
            long combat = brain.getCombatController()
                .getTicks(),
                follow = brain.getFollowController()
                    .getTicks();
            require(runtime.select(profile) == SelectionResult.UNAVAILABLE, "unimplemented profile accepted");
            require(runtime.getProfile() == profile && !runtime.isReady(), "unavailable profile fell back");
            require(
                runtime.tick(() -> { throw new IllegalStateException("Unavailable policy queried life"); })
                    == TickResult.UNAVAILABLE,
                "unavailable policy dispatched");
            brain.resetTask();
            require(
                brain.peekPolicyRuntime() == runtime && runtime.getProfile() == profile && !runtime.isReady(),
                "reset silently restored legacy instead of retaining unavailable profile");
            require(
                combat == brain.getCombatController()
                    .getTicks() && follow
                        == brain.getFollowController()
                            .getTicks(),
                "unavailable profile advanced controllers");
        }
        require(
            runtime.select(MaidBrainProfile.LEGACY_PROTOTYPE) == SelectionResult.SELECTED && runtime.isReady(),
            "explicit legacy recovery");
        passed("unavailable_f_n_no_fallback", "selection, tick and reset preserve explicit unavailability");
    }

    private void unloadAndRegister() {
        var maid = remember(new EntityMaid(world));
        var brain = maid.getBrainCoordinator();
        var old = brain.getPolicyRuntime();
        var token = old.getContext()
            .getToken();
        brain.onUnload();
        require(
            old.isRetired() && !old.getContext()
                .isCurrent(token),
            "unload retained old runtime");
        require(!brain.onRegistered(), "unregistered entity rearmed");
        require(
            brain.getPolicyRuntime() == old && !brain.getPolicyRuntime()
                .isReady(),
            "getter rearmed after unload");
        brain.updateTask();
        require(brain.peekPolicyRuntime() == old && old.isRetired(), "unregistered update rearmed old wrapper");
        require(world.spawnEntityInWorld(maid), "native fixture registration rejected");
        require(
            world.getEntityByID(maid.getEntityId()) == maid && world.loadedEntityList.contains(maid),
            "native ID/list registration incomplete");
        require(brain.onRegistered(), "registered alive entity not allowed to create new runtime");
        var current = brain.getPolicyRuntime();
        require(current != old && current.isReady(), "registered generation did not obtain fresh runtime");
        require(
            !current.getContext()
                .isCurrent(token) && old.tick(() -> true) == TickResult.RETIRED,
            "old reference or token authorized after registration");
        passed("unload_then_native_registration", "alive alone cannot rearm; native ID/list/chunk registration can");
    }

    private void failedReadRemainsBlocked() {
        var maid = remember(new FailingReadMaid(world));
        var brain = maid.getBrainCoordinator();
        var old = brain.getPolicyRuntime();
        var tag = save(maid);
        maid.failRead = true;
        boolean rejected = false;
        try {
            maid.readFromNBT(tag);
        } catch (RuntimeException expected) {
            rejected = true;
        }
        require(rejected && brain.isLifecycleFailed() && old.isRetired(), "native read failure was not latched");
        maid.failRead = false;
        maid.readFromNBT(tag);
        require(
            brain.getPolicyRuntime() == old && !brain.onRegistered() && !old.isReady(),
            "later successful read cleared permanent failure latch");
        passed("failed_native_read_stays_blocked", "native exception rejected and later read cannot silently recover");
    }

    private void loadedListIdentity() {
        var maid = remember(new EntityMaid(world));
        require(world.spawnEntityInWorld(maid), "native identity fixture registration rejected");
        var brain = maid.getBrainCoordinator();
        var old = brain.getPolicyRuntime();
        var token = old.getContext()
            .getToken();
        brain.onUnload();
        int slot = loadedSlot(maid);
        require(slot >= 0 && world.getEntityByID(maid.getEntityId()) == maid, "native identity fixture not registered");
        require(
            maid.addedToChunk && world.getChunkFromChunkCoords(maid.chunkCoordX, maid.chunkCoordZ).isChunkLoaded,
            "native identity fixture chunk state");
        // 替身从不登记到世界，也不交给removeEntity；相同ID会干扰原生移除映射
        var substitute = new EntityMaid(world);
        substitute.setEntityId(maid.getEntityId());
        boolean replaced = false;
        try {
            require(substitute != maid && substitute.equals(maid), "native equals alias fixture");
            world.loadedEntityList.set(slot, substitute);
            replaced = true;
            require(world.getEntityByID(maid.getEntityId()) == maid, "slot replacement changed the actual ID mapping");
            require(
                maid.addedToChunk && world.getChunkFromChunkCoords(maid.chunkCoordX, maid.chunkCoordZ).isChunkLoaded,
                "slot replacement changed chunk registration");
            require(
                world.loadedEntityList.contains(maid) && loadedSlot(maid) < 0,
                "native equals-based contains must be true while the actual object is absent");
            require(!brain.onRegistered(), "equal entity ID falsely authorized world re-registration");
            brain.updateTask();
            require(
                brain.peekPolicyRuntime() == old && brain.getPolicyRuntime() == old
                    && old.isRetired()
                    && !old.getContext()
                        .isCurrent(token),
                "identity-missing actor was rearmed by getter or update");
        } finally {
            try {
                if (replaced) {
                    require(
                        slot < world.loadedEntityList.size() && world.loadedEntityList.get(slot) == substitute,
                        "fixture slot ownership changed before restoration");
                    world.loadedEntityList.set(slot, maid);
                }
            } finally {
                substitute.setDead();
            }
        }
        require(
            world.loadedEntityList.get(slot) == maid && world.getEntityByID(maid.getEntityId()) == maid,
            "fixture identity slot was not restored");
        require(brain.onRegistered(), "restored actual object cannot register");
        var fresh = brain.getPolicyRuntime();
        require(
            fresh != old && fresh.isReady()
                && !fresh.getContext()
                    .isCurrent(token),
            "restored identity did not produce a fresh runtime token");
        passed(
            "loaded_list_requires_object_identity",
            "native same-ID alias rejected with ID map/chunk intact; owned slot restored and fresh runtime allowed");
    }

    private int loadedSlot(EntityMaid maid) {
        for (int i = 0; i < world.loadedEntityList.size(); i++) if (world.loadedEntityList.get(i) == maid) return i;
        return -1;
    }

    private void chunkFailureIsolation() {
        var broken = remember(new ThrowingCancelMaid(world));
        var healthy = remember(new EntityMaid(world));
        var brokenRuntime = broken.getBrainCoordinator()
            .getPolicyRuntime();
        var healthyRuntime = healthy.getBrainCoordinator()
            .getPolicyRuntime();
        var isolatedChunk = new Chunk(world, 0, 0);
        isolatedChunk.entityLists[0].add(broken);
        isolatedChunk.entityLists[0].add(healthy);
        broken.failNavigation = true;
        new MaidBrainLifecycle().onChunkUnload(new ChunkEvent.Unload(isolatedChunk));
        require(
            broken.getBrainCoordinator()
                .isLifecycleFailed() && brokenRuntime.isRetired(),
            "cleanup failure did not latch its actor");
        require(healthyRuntime.isRetired(), "one cleanup failure skipped another actor");
        broken.failNavigation = false;
        require(
            broken.getBrainCoordinator()
                .getPolicyRuntime() == brokenRuntime && !brokenRuntime.isReady(),
            "cleanup failure allowed getter resurrection");
        passed(
            "chunk_handler_failure_isolation",
            "synthetic chunk routes actual actor cleanup; injected failure stays blocked");
    }

    private <T extends EntityMaid> T remember(T maid) {
        actors.add(maid);
        var spawn = world.getSpawnPoint();
        require(
            world.getChunkProvider()
                .chunkExists(spawn.posX >> 4, spawn.posZ >> 4),
            "fixture chunk is not loaded");
        maid.setPosition(spawn.posX + 0.5, Math.min(240, Math.max(4, spawn.posY + 2)), spawn.posZ + 0.5);
        return maid;
    }

    private void cleanup() {
        Throwable failure = null;
        for (var maid : actors) {
            if (maid instanceof ThrowingCancelMaid broken) broken.failNavigation = false;
            try {
                world.removeEntity(maid);
                require(maid.isDead, "native cleanup did not mark fixture dead");
            } catch (RuntimeException | Error problem) {
                if (failure == null) failure = problem;
                else if (failure != problem) failure.addSuppressed(problem);
            }
        }
        if (failure instanceof RuntimeException problem) throw problem;
        if (failure instanceof Error problem) throw problem;
    }

    private static NBTTagCompound save(EntityMaid maid) {
        var tag = new NBTTagCompound();
        maid.writeToNBT(tag);
        return tag;
    }

    private void passed(String name, String detail) {
        var result = new JsonObject();
        result.addProperty("case", name);
        result.addProperty("passed", true);
        result.addProperty("detail", detail);
        cases.add(result);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException("Policy lifecycle probe failed: " + reason);
    }

    private static final class ReadObservingMaid extends EntityMaid {

        private MaidPolicyRuntime expectedRuntime;
        private MaidPolicyContext.Token expectedToken;
        private boolean observedRetiredBeforeChild;

        private ReadObservingMaid(World world) {
            super(world);
        }

        @Override
        public void readEntityFromNBT(NBTTagCompound tag) {
            if (expectedRuntime != null) {
                require(
                    expectedRuntime.isRetired() && !expectedRuntime.getContext()
                        .isCurrent(expectedToken),
                    "old token was still active before native child read");
                observedRetiredBeforeChild = true;
            }
            super.readEntityFromNBT(tag);
        }
    }

    private static final class FailingReadMaid extends EntityMaid {

        private boolean failRead;

        private FailingReadMaid(World world) {
            super(world);
        }

        @Override
        public void readEntityFromNBT(NBTTagCompound tag) {
            super.readEntityFromNBT(tag);
            if (failRead) throw new IllegalStateException("Injected lifecycle NBT failure");
        }
    }

    private static final class ThrowingCancelMaid extends EntityMaid {

        private boolean failNavigation;

        private ThrowingCancelMaid(World world) {
            super(world);
        }

        @Override
        public PathNavigate getNavigator() {
            if (failNavigation) throw new IllegalStateException("Injected lifecycle cleanup failure");
            return super.getNavigator();
        }
    }
}
