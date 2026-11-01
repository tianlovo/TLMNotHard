package com.tianluoqaq.tlmnh.qa;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidAmphibiousPath;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNavigator;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class AmphibiousNavigationTests {

    private AmphibiousNavigationTests() {}

    @GameTest(template = "navigation_cell", timeoutTicks = 190, batch = "nav_deep")
    public static void rootFollowsThreeDimensionalWaterPath(GameTestHelper helper) {
        var fixture = new Fixture(helper, "deep");
        helper.onEachTick("native registered movement", fixture::check);
        helper.succeedWhen(
            () -> fixture.sawModern && fixture.sawReachable
                && fixture.maid.posX > fixture.x + 8.2
                && fixture.maid.posY > fixture.y + 3.1);
    }

    @GameTest(template = "navigation_cell", timeoutTicks = 190, batch = "nav_tunnel")
    public static void rootSwimsThroughOneBlockTunnel(GameTestHelper helper) {
        var fixture = new Fixture(helper, "tunnel");
        helper.onEachTick("low physical ceiling", () -> {
            fixture.check();
            if (fixture.maid.posX > fixture.x + 5 && fixture.maid.posX < fixture.x + 7.7) helper.assertTrue(
                fixture.maid.height < .61 && fixture.maid.boundingBox.maxY <= fixture.y + 2.00001,
                "Body does not fit tunnel");
        });
        helper.succeedWhen(() -> fixture.sawModern && fixture.sawReachable && fixture.maid.posX > fixture.x + 8.2);
    }

    @GameTest(template = "navigation_cell", timeoutTicks = 125, batch = "nav_blocked_cancel")
    public static void partialRouteNeverCrossesWallAndSittingCancels(GameTestHelper helper) {
        var fixture = new Fixture(helper, "blocked");
        final int[] stop = { -1 };
        final boolean[] returning = { false };
        helper.onEachTick("partial path and real cancellation", () -> {
            fixture.check();
            helper.assertTrue(fixture.maid.posX <= fixture.x + 5.70001, "Maid crossed sealed wall");
            var path = fixture.maid.getNavigator()
                .getPath();
            if (!returning[0] && path instanceof MaidAmphibiousPath modern)
                helper.assertFalse(modern.canReach(), "Sealed target reported reachable");
            if (!returning[0] && fixture.maid.ticksExisted >= 80) {
                helper.assertTrue(fixture.sawModern, "No production water path observed");
                helper.assertTrue(fixture.maid.posX > fixture.x + 5.5, "Partial route never reached the wall");
                fixture.owner.setPosition(fixture.x + 1.5, fixture.y + 2, fixture.z + 3.5);
                returning[0] = true;
            }
            if (returning[0] && stop[0] < 0
                && fixture.maid.ticksExisted > 80
                && path instanceof MaidAmphibiousPath modern
                && modern.canReach()
                && !modern.isFinished()) {
                System.out.println(
                    "TLMNH_NAV96_CANCEL_LIVE_ROUTE index=" + modern.getCurrentPathIndex()
                        + "/"
                        + modern.getCurrentPathLength());
                fixture.maid.setSitting(true);
                stop[0] = fixture.maid.ticksExisted;
            }
            if (stop[0] >= 0 && fixture.maid.ticksExisted > stop[0] + 2) helper.assertTrue(
                fixture.maid.getNavigator()
                    .noPath(),
                "Sitting retained/reinstalled old route");
        });
        helper.succeedWhen(
            () -> stop[0] >= 0 && fixture.maid.ticksExisted >= stop[0] + 10
                && fixture.maid.getNavigator()
                    .noPath());
    }

    @GameTest(template = "navigation_cell", timeoutTicks = 150, batch = "nav_ground")
    public static void groundFollowKeepsNativeRouteExecution(GameTestHelper helper) {
        var fixture = new Fixture(helper, "ground");
        helper.onEachTick("native ground branch", () -> {
            fixture.check();
            var path = fixture.maid.getNavigator()
                .getPath();
            helper.assertFalse(path instanceof MaidAmphibiousPath, "Dry route was unexpectedly replaced");
        });
        helper.succeedWhen(() -> fixture.maid.posX > fixture.x + 8.2 && fixture.maid.ticksExisted > 2);
    }

    private static final class Fixture {

        final GameTestHelper helper;
        final EntityMaid maid;
        final EntityPlayerMP owner;
        final int x, y, z;
        final String kind;
        boolean sawModern, sawReachable;
        double previousX, previousY, previousZ;

        Fixture(GameTestHelper helper, String kind) {
            this.helper = helper;
            this.kind = kind;
            var origin = helper.absolute(0, 0, 0);
            x = origin.x();
            y = origin.y();
            z = origin.z();
            var server = MinecraftServer.getServer();
            boolean animals = server.getCanSpawnAnimals();
            helper.afterTest(() -> {
                server.setCanSpawnAnimals(animals);
                helper.assertEquals(animals, server.getCanSpawnAnimals(), "Animal policy restored");
            });
            server.setCanSpawnAnimals(true);
            var chunks = new GameTestChunkLoader();
            helper.afterTest(() -> {
                chunks.releaseAll();
                System.out.println("TLMNH_NAV96_CHUNKS_RELEASED kind=" + kind);
            });
            try {
                chunks.forceChunksStrict(helper.getWorld(), x - 24, y, z - 24, x + 36, y + 6, z + 30);
            } catch (Exception failure) {
                throw new IllegalStateException("Path-query preparation area could not be held", failure);
            }
            owner = new EntityPlayerMP(
                server,
                helper.getWorld(),
                new GameProfile(UUID.randomUUID(), "[QA-Nav]"),
                new ItemInWorldManager(helper.getWorld()));
            var manager = new NetworkManager(false);
            var connection = new EmbeddedChannel(manager);
            manager.setConnectionState(EnumConnectionState.PLAY);
            IsolatedPlayerChannel.verify(manager, connection);
            new NetHandlerPlayServer(server, manager, owner);
            maid = new EntityMaid(helper.getWorld());
            helper.afterTest(() -> {
                helper.getWorld().playerEntities.remove(owner);
                owner.setDead();
                connection.finish();
                Object message;
                while ((message = connection.readOutbound()) != null) ReferenceCountUtil.release(message);
                while ((message = connection.readInbound()) != null) ReferenceCountUtil.release(message);
                maid.setDead();
                helper.getWorld()
                    .removePlayerEntityDangerously(maid);
                for (int px = 0; px < 13; px++) for (int py = 0; py < 7; py++)
                    for (int pz = 0; pz < 7; pz++) helper.setBlock(px, py, pz, Blocks.air);
                helper.assertFalse(helper.getWorld().loadedEntityList.contains(maid), "Maid retained after cleanup");
                helper.assertFalse(helper.getWorld().playerEntities.contains(owner), "Synthetic owner retained");
                System.out.println(
                    "TLMNH_NAV96_CLEANUP_PASS kind=" + kind
                        + " ticks="
                        + maid.ticksExisted
                        + " modern="
                        + sawModern
                        + " reachable="
                        + sawReachable
                        + " xyz="
                        + maid.posX
                        + ","
                        + maid.posY
                        + ","
                        + maid.posZ);
            });
            for (int px = 0; px < 13; px++) for (int py = 0; py < 7; py++) for (int pz = 0; pz < 7; pz++) {
                boolean solid = py == 0 || !kind.equals("ground") && (px == 0 || px == 12
                    || py == 6
                    || pz == 0
                    || pz == 6
                    || kind.equals("blocked") && px == 6
                    || kind.equals("tunnel") && px >= 5 && px <= 7 && py >= 2);
                helper.setBlock(px, py, pz, solid ? Blocks.glass : kind.equals("ground") ? Blocks.air : Blocks.water);
            }
            double startY = kind.equals("deep") || kind.equals("blocked") ? 2 : 1;
            double targetY = kind.equals("deep") || kind.equals("blocked") ? 4 : 1;
            owner.setPosition(x + 11.5, y + targetY, z + 3.5);
            owner.capabilities.disableDamage = true;
            helper.getWorld().playerEntities.add(owner);
            helper.assertTrue(maid.bindTo(owner.getUniqueID()), "Owner binding failed");
            helper.spawnEntity(maid, 2.5, startY, 3.5);
            helper.assertTrue(
                maid.getNavigator() instanceof MaidPathNavigator,
                "Native internal navigator was not replaced");
            previousX = maid.posX;
            previousY = maid.posY;
            previousZ = maid.posZ;
        }

        void check() {
            helper.assertTrue(maid.isEntityAlive() && maid.getHealth() == 20, "Maid harmed during navigation");
            helper.assertFalse(
                maid.getBrainCoordinator()
                    .getBreathController()
                    .isActive(),
                "Low-air rescue masked ordinary navigation");
            double dx = maid.posX - previousX, dy = maid.posY - previousY, dz = maid.posZ - previousZ;
            helper.assertTrue(dx * dx + dy * dy + dz * dz < 1, "Teleport or position injection masked path execution");
            previousX = maid.posX;
            previousY = maid.posY;
            previousZ = maid.posZ;
            helper.assertTrue(
                helper.getWorld()
                    .func_147461_a(maid.boundingBox.contract(.001, .001, .001))
                    .isEmpty(),
                "Native collision body clipped into fixture");
            var path = maid.getNavigator()
                .getPath();
            if (path instanceof MaidAmphibiousPath modern) {
                sawModern = true;
                sawReachable |= modern.canReach();
            }
            if (maid.ticksExisted % 20 == 0) System.out.println(
                "TLMNH_NAV96_TRACE kind=" + kind
                    + " ticks="
                    + maid.ticksExisted
                    + " xyz="
                    + maid.posX
                    + ","
                    + maid.posY
                    + ","
                    + maid.posZ
                    + " air="
                    + maid.getAir()
                    + " body="
                    + maid.height
                    + " mode="
                    + maid.getBrainCoordinator()
                        .getFollowController()
                        .getStatus()
                    + " result="
                    + maid.getBrainCoordinator()
                        .getFollowController()
                        .getLastPathResult()
                    + " diagnostic="
                    + (maid.getBrainCoordinator()
                        .getLastPathAttempt() == null ? "none"
                            : maid.getBrainCoordinator()
                                .getLastPathAttempt().diagnostic)
                    + " path="
                    + (path == null ? "null" : path.getCurrentPathIndex() + "/" + path.getCurrentPathLength()));
        }
    }
}
