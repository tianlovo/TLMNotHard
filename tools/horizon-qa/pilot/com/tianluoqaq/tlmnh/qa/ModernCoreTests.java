package com.tianluoqaq.tlmnh.qa;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.util.FakePlayer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveState;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreComponent;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidModernPath;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNavigator;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathOwnership;
import com.tianluoqaq.tlmnh.common.config.MaidHomeConfig;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ModernCoreTests {

    private ModernCoreTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 260, batch = "modern_core_dynamic")
    public static void dynamicOwnerUsesModernSinkWithoutTenTickRequery(GameTestHelper helper) {
        var f = new Fixture(helper, false);
        boolean[] moved = { false }, replanned = { false };
        long[] deadline = { -1 };
        boolean[] stopped = { false };
        helper.onEachTick("real after-navigation phase and live target", () -> {
            f.check();
            int tick = f.maid.ticksExisted;
            if (f.core.getMoveState()
                .getStatus() == FollowMoveState.Status.RUNNING) {
                if (deadline[0] < 0) deadline[0] = f.core.getMoveState()
                    .getEndTimestamp();
                helper.assertTrue(
                    deadline[0] == f.core.getMoveState()
                        .getEndTimestamp(),
                    "Replanning extended the original Behavior duration");
            } else if (deadline[0] >= 0 && !stopped[0]) {
                boolean reached = Math.abs((int) Math.floor(f.owner.posX) - (int) Math.floor(f.maid.posX))
                    + Math.abs((int) Math.floor(f.owner.posY) - (int) Math.floor(f.maid.posY))
                    + Math.abs((int) Math.floor(f.owner.posZ) - (int) Math.floor(f.maid.posZ)) <= 2;
                helper.assertTrue(
                    reached || f.maid.worldObj.getTotalWorldTime() > deadline[0],
                    "Modern movement stopped before arrival or the original Behavior deadline");
                helper.assertTrue(
                    f.maid.posX > f.x + 6 && f.navigation.searchCount() == 2,
                    "Bounded Behavior stopped without executing the live replanned route");
                stopped[0] = true;
                System.out.println(
                    "TLMNH_CORE98_BOUNDED_STOP tick=" + tick + " reached=" + reached + " deadline=" + deadline[0]);
            }
            if (tick == 1) helper.assertTrue(
                f.navigation.noPath() && f.navigation.searchCount() == 0
                    && f.core.getActor()
                        .peekWalkTarget() != null,
                "Follow created a path in the wrong start phase");
            if (tick >= 3 && tick <= 17) {
                helper.assertTrue(
                    f.navigation.searchCount() == 1,
                    "Stationary/nearby owner caused repeated path queries");
                helper.assertTrue(
                    f.core.getActor()
                        .peekPathMemory()
                        .getIdentity() == f.navigation.getPath(),
                    "PATH memory is not the actual installed route");
            }
            if (tick == 8) f.owner.setPosition(f.x + 12.5, f.y + 1, f.z + 4.5);
            if (tick >= 9 && tick <= 17) helper.assertTrue(
                f.navigation.walkTarget().x == f.x + 12,
                "Navigation manager froze the original WALK_TARGET position");
            if (tick == 18) {
                moved[0] = true;
                f.owner.setPosition(f.x + 14.5, f.y + 1, f.z + 4.5);
            }
            if (tick > 18 && !replanned[0] && f.navigation.searchCount() == 2) {
                replanned[0] = true;
                helper.assertTrue(
                    f.navigation.getPath() instanceof MaidModernPath path && path.result.targetX == f.x + 14
                        && path.canReach(),
                    "Moved owner did not get a real reachable modern route");
            }
        });
        helper.succeedWhen(
            () -> moved[0] && replanned[0]
                && stopped[0]
                && f.core.getMoveState()
                    .getStatus() == FollowMoveState.Status.STOPPED
                && f.core.getActor()
                    .peekWalkTarget() == null
                && f.navigation.noPath());
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 240, batch = "modern_core_water")
    public static void waterContinuationUpdatesPathMemoryAndCancelsLiveRoute(GameTestHelper helper) {
        var f = new Fixture(helper, true);
        boolean[] modern = { false };
        int[] stopped = { -1 };
        PathEntity[] pending = { null };
        int[] handoffTick = { -1 };
        helper.onEachTick("real three-dimensional modern core", () -> {
            f.check();
            var path = f.navigation.getPath();
            if (pending[0] != null && path == pending[0] && f.maid.ticksExisted > handoffTick[0]) {
                helper.assertTrue(
                    f.core.getActor()
                        .peekPathMemory() != null
                        && f.core.getActor()
                            .peekPathMemory()
                            .getIdentity() == path,
                    "Sink failed to adopt the previous frame's continuation");
                pending[0] = null;
            }
            if (path instanceof MaidModernPath current && !current.isFinished() && f.navigation.usesWaterNavigation()) {
                modern[0] = true;
                var owned = f.core.getActor()
                    .peekOwnedPath();
                if (owned != path) {
                    // 管理器在living末尾换路，Sink在下一帧导航后更新PATH
                    helper.assertTrue(MaidPathOwnership.owns(path, owned), "Unowned mode continuation");
                    pending[0] = path;
                    handoffTick[0] = f.maid.ticksExisted;
                } else helper.assertTrue(
                    f.core.getActor()
                        .peekPathMemory() != null
                        && f.core.getActor()
                            .peekPathMemory()
                            .getIdentity() == path,
                    "PATH memory missed the active route");
            }
            if (stopped[0] < 0 && modern[0]
                && f.maid.posX > f.x + 7.5
                && f.maid.posY > f.y + 4
                && path != null
                && !path.isFinished()) {
                System.out.println(
                    "TLMNH_CORE98_LIVE_CANCEL index=" + path.getCurrentPathIndex() + "/" + path.getCurrentPathLength());
                f.maid.setSitting(true);
                stopped[0] = f.maid.ticksExisted;
            }
            if (stopped[0] >= 0 && f.maid.ticksExisted > stopped[0]) helper.assertTrue(
                f.navigation.noPath() && f.core.getActor()
                    .peekWalkTarget() == null,
                "Movement restriction retained modern core movement");
        });
        helper.succeedWhen(() -> stopped[0] >= 0 && f.maid.ticksExisted >= stopped[0] + 10);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 330, batch = "modern_core_watchdog")
    public static void realWatchdogStopsBlockedRouteAndPartialPathSetsReachMemory(GameTestHelper helper) {
        var f = new Fixture(helper, false);
        boolean[] blocked = { false }, watched = { false }, partial = { false };
        helper.onEachTick("native collision, actual modern watchdog and reach memory", () -> {
            f.check();
            int tick = f.maid.ticksExisted;
            // 在首次100tick检测窗内阻挡，避免Behavior的150～250tick上限先于第二检测窗结束
            if (!blocked[0] && tick >= 3 && f.navigation.getPath() != null) {
                for (int y = 1; y < 10; y++) for (int z = 1; z < 8; z++) helper.setBlock(2, y, z, Blocks.glass);
                blocked[0] = true;
            }
            var stop = f.navigation.lastStop();
            if (!watched[0] && stop != null && stop.cause == MaidPathNavigator.StopCause.WATCHDOG) {
                watched[0] = true;
                int cooldown = f.core.getMoveState()
                    .getRemainingCooldown();
                helper.assertTrue(cooldown >= 0 && cooldown < 40, "Modern sink cooldown outside world RNG range");
                f.owner.setPosition(f.x + 13.5, f.y + 1, f.z + 4.5);
                System.out.println("TLMNH_CORE98_WATCHDOG tick=" + tick + " cooldown=" + cooldown);
            }
            var created = f.core.getActor()
                .peekLastCreated();
            if (watched[0] && created != null && !created.canReach()) {
                helper.assertTrue(
                    f.core.getActor()
                        .peekCantReachSince() != null,
                    "Partial modern path fabricated reachability or omitted CANT_REACH memory");
                helper.assertTrue(f.maid.posX <= f.x + 1.70001, "Maid crossed sealed wall");
                partial[0] = true;
            }
        });
        helper.succeedWhen(() -> partial[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 50, batch = "modern_core_ownership")
    public static void staleCreationAndRetiredActorDoNotOverwriteForeignPath(GameTestHelper helper) {
        var f = new Fixture(helper, false);
        boolean[] checked = { false };
        helper.onEachTick("controlled stale creation and foreign successor", () -> {
            f.check();
            if (checked[0] || f.maid.ticksExisted < 8 || f.navigation.noPath()) return;
            var actor = f.core.getActor();
            var oldOwner = actor.getOwner();
            PathEntity actual = f.navigation.getPath();
            var created = f.navigation.createBrainPath(actor, f.x + 18, f.y + 1, f.z + 4, 0);
            long searched = f.navigation.searchCount();
            var cached = f.navigation.createBrainPath(actor, f.x + 18, f.y + 1, f.z + 4, 0);
            helper.assertTrue(
                cached.path == actual && searched == f.navigation.searchCount(),
                "Modern create-only target metadata/cache phase changed");
            f.navigation.activate(f.navigation.waterNavigation());
            boolean rejected = false;
            try {
                f.navigation.installBrainPath(actor, created, .5);
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "Mode-changed creation was installed");
            f.navigation.activate(f.navigation.ground());
            helper.assertTrue(f.navigation.getPath() == actual, "Rejected creation disturbed the real route");
            var foreign = f.navigation.createPath(f.x + 18, f.y + 1, f.z + 4, 64, 0);
            helper.assertTrue(
                f.navigation.setPath(foreign, .5) && f.navigation.getPath() == foreign,
                "Distinct foreign successor fixture not installed");
            f.core.cancel();
            helper.assertTrue(f.navigation.getPath() == foreign, "Actor cancellation cleared foreign successor");
            f.maid.getBrainCoordinator()
                .setAfterNavigationActionsForDevelopment(null);
            helper.assertTrue(f.navigation.getPath() == foreign, "Policy retirement cleared foreign successor");
            long before = f.navigation.searchCount();
            boolean expired = false;
            try {
                actor.teleportToOwner(oldOwner);
            } catch (IllegalStateException expected) {
                expired = true;
            }
            helper.assertTrue(
                expired && before == f.navigation.searchCount() && f.navigation.getPath() == foreign,
                "Retired actor still changed navigation");
            checked[0] = true;
            System.out.println("TLMNH_CORE98_FOREIGN_CANCEL_PASS");
        });
        helper.succeedWhen(() -> checked[0]);
    }

    static final class Fixture {

        final GameTestHelper helper;
        final EntityMaid maid;
        final EntityPlayerMP owner;
        final ModernFollowCoreComponent core;
        final MaidPathNavigator navigation;
        final int x, y, z;
        double previousX, previousY, previousZ;
        final double maxDisplacementSquared;

        Fixture(GameTestHelper helper, boolean wet) {
            this(helper, wet, 1);
        }

        Fixture(GameTestHelper helper, boolean wet, double maxDisplacementSquared) {
            this.maxDisplacementSquared = maxDisplacementSquared;
            this.helper = helper;
            var origin = helper.absolute(0, 0, 0);
            x = origin.x();
            y = origin.y();
            z = origin.z();
            var server = MinecraftServer.getServer();
            boolean animals = server.getCanSpawnAnimals();
            long time = helper.getWorld()
                .getWorldTime();
            int range = MaidHomeConfig.workRange;
            server.setCanSpawnAnimals(true);
            helper.getWorld()
                .setWorldTime(6000);
            MaidHomeConfig.workRange = 12;
            helper.afterTest(() -> {
                server.setCanSpawnAnimals(animals);
                helper.getWorld()
                    .setWorldTime(time);
                MaidHomeConfig.workRange = range;
            });
            var chunks = new GameTestChunkLoader();
            helper.afterTest(chunks::releaseAll);
            try {
                chunks.forceChunksStrict(helper.getWorld(), x - 80, y, z - 80, x + 100, y + 10, z + 90);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            owner = new FakePlayer(helper.getWorld(), new GameProfile(UUID.randomUUID(), "[QA-Modern]"));
            var manager = new NetworkManager(false);
            var connection = new EmbeddedChannel(manager);
            manager.setConnectionState(EnumConnectionState.PLAY);
            IsolatedPlayerChannel.verify(manager, connection);
            new NetHandlerPlayServer(server, manager, owner);
            maid = new EntityMaid(helper.getWorld());
            helper.afterTest(() -> {
                maid.getBrainCoordinator()
                    .setSuspended(true);
                maid.setDead();
                helper.getWorld()
                    .removePlayerEntityDangerously(maid);
                helper.getWorld().playerEntities.remove(owner);
                server.getConfigurationManager().playerEntityList.remove(owner);
                owner.setDead();
                connection.finish();
                Object message;
                while ((message = connection.readOutbound()) != null) ReferenceCountUtil.release(message);
                while ((message = connection.readInbound()) != null) ReferenceCountUtil.release(message);
                for (int a = 0; a < 21; a++)
                    for (int b = 0; b < 11; b++) for (int c = 0; c < 9; c++) helper.setBlock(a, b, c, Blocks.air);
                System.out.println("TLMNH_CORE98_CLEANUP_PASS wet=" + wet + " ticks=" + maid.ticksExisted);
            });
            for (int a = 0; a < 21; a++) for (int b = 0; b < 11; b++) for (int c = 0; c < 9; c++) {
                boolean solid = a == 0 || a == 20 || b == 0 || b == 10 || c == 0 || c == 8;
                helper.setBlock(a, b, c, solid ? Blocks.glass : wet ? Blocks.water : Blocks.air);
            }
            owner.setPosition(x + 11.5, y + (wet ? 6 : 1), z + 4.5);
            owner.capabilities.disableDamage = true;
            helper.getWorld().playerEntities.add(owner);
            server.getConfigurationManager().playerEntityList.add(owner);
            helper.assertTrue(maid.bindTo(owner.getUniqueID()), "Fresh owner binding failed");
            maid.setPickup(false);
            helper.spawnEntity(maid, 1.5, 1, 4.5);
            core = new ModernFollowCoreComponent(maid);
            navigation = (MaidPathNavigator) maid.getNavigator();
            maid.getBrainCoordinator()
                .setAfterNavigationActionsForDevelopment(core);
            previousX = maid.posX;
            previousY = maid.posY;
            previousZ = maid.posZ;
        }

        void check() {
            check(20);
        }

        void check(float expectedHealth) {
            helper.assertTrue(
                maid.isEntityAlive() && maid.getHealth() == expectedHealth,
                "Modern core actor health differs");
            helper.assertFalse(
                maid.getBrainCoordinator()
                    .getBreathController()
                    .isActive(),
                "Legacy low-air masked modern core");
            helper.assertTrue(
                maid.getBrainCoordinator()
                    .peekPolicyRuntime()
                    .isReady(),
                "Modern core policy faulted");
            double dx = maid.posX - previousX, dy = maid.posY - previousY, dz = maid.posZ - previousZ;
            helper.assertTrue(dx * dx + dy * dy + dz * dz < maxDisplacementSquared, "Discontinuous core movement");
            previousX = maid.posX;
            previousY = maid.posY;
            previousZ = maid.posZ;
            helper.assertTrue(
                core.getActor()
                    .teleportAttempts() == 0,
                "Unexpected teleport replaced route execution");
            helper.assertTrue(
                helper.getWorld()
                    .func_147461_a(maid.boundingBox.contract(.001, .001, .001))
                    .isEmpty(),
                "Modern core body clipped through solid fixture");
            if (maid.ticksExisted > 0 && maid.ticksExisted % 20 == 0) {
                var path = navigation.getPath();
                System.out.println(
                    "TLMNH_CORE98_TRACE tick=" + maid.ticksExisted
                        + " xyz="
                        + maid.posX
                        + ","
                        + maid.posY
                        + ","
                        + maid.posZ
                        + " mode="
                        + navigation.getManager()
                            .mode()
                        + " water="
                        + navigation.usesWaterNavigation()
                        + " searches="
                        + navigation.searchCount()
                        + " path="
                        + (path == null ? "null" : path.getCurrentPathIndex() + "/" + path.getCurrentPathLength()));
            }
        }
    }
}
