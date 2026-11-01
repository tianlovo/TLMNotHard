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
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidNavigationManager;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidModernPath;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNavigator;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathOwnership;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class TraversalNavigationTests {

    private TraversalNavigationTests() {}

    @GameTest(template = "navigation_cell", timeoutTicks = 190, batch = "traversal_tunnel")
    public static void existingLowTunnelStillUsesNaturalMovement(GameTestHelper helper) {
        AmphibiousNavigationTests.rootSwimsThroughOneBlockTunnel(helper);
    }

    @GameTest(template = "navigation_cell", timeoutTicks = 190, batch = "traversal_doors")
    public static void doorsAndGateRespectCapabilitiesAndCloseAfterPassing(GameTestHelper helper) {
        var f = new Fixture(helper, "doors");
        int[] phase = { 0 }, changed = { 0 };
        boolean[] door = { false }, gate = { false };
        helper.onEachTick("door traversal and permission", () -> {
            f.check();
            door[0] |= f.open(4);
            gate[0] |= f.open(6);
            if (phase[0] == 0 && f.maid.posX > f.x + 8.2) {
                helper.assertTrue(door[0] && gate[0], "Door or gate was bypassed");
                phase[0] = 1;
                changed[0] = f.maid.ticksExisted;
            }
            if (phase[0] == 1 && f.maid.ticksExisted >= changed[0] + 15) {
                helper.assertFalse(f.open(4) || f.open(6), "Passed door/gate remained open");
                f.maid.getConfigManager()
                    .setOpenDoor(false);
                f.maid.getConfigManager()
                    .setOpenFenceGate(false);
                f.owner.setPosition(f.x + 1.5, f.y + 1, f.z + 3.5);
                phase[0] = 2;
                changed[0] = f.maid.ticksExisted;
            }
            if (phase[0] == 2) {
                helper.assertFalse(f.open(4) || f.open(6), "Disabled capability reopened barrier");
                helper.assertTrue(f.maid.posX >= f.x + 6.6999, "Maid crossed disabled gate");
            }
        });
        helper.succeedWhen(() -> phase[0] == 2 && f.maid.ticksExisted >= changed[0] + 30);
    }

    @GameTest(template = "navigation_cell", timeoutTicks = 260, batch = "traversal_ladder")
    public static void distantLadderJoinsGroundRouteAndReturnsDown(GameTestHelper helper) {
        var f = new Fixture(helper, "ladder");
        boolean[] reached = { false }, sawVertical = { false };
        helper.onEachTick("ladder route and physical ascent/descent", () -> {
            f.check();
            var path = f.maid.getNavigator()
                .getPath();
            if (path != null) for (int i = 1; i < path.getCurrentPathLength(); i++) {
                var previous = path.getPathPointFromIndex(i - 1);
                var next = path.getPathPointFromIndex(i);
                sawVertical[0] |= previous.xCoord == next.xCoord && previous.zCoord == next.zCoord
                    && previous.yCoord != next.yCoord;
            }
            if (!reached[0] && f.maid.posX > f.x + 8.2 && f.maid.posY >= f.y + 3.9) {
                helper.assertTrue(sawVertical[0], "No real remote-ladder path edge observed");
                reached[0] = true;
                f.owner.setPosition(f.x + 1.5, f.y + 1, f.z + 3.5);
            }
        });
        helper.succeedWhen(() -> reached[0] && f.maid.posX < f.x + 4.7 && f.maid.posY < f.y + 1.3);
    }

    @GameTest(template = "navigation_cell", timeoutTicks = 320, batch = "traversal_shore")
    public static void groundWaterShoreSwitchKeepsCancellationOwnership(GameTestHelper helper) {
        var f = new Fixture(helper, "shore");
        boolean[] entered = { false }, landed = { false };
        int[] stopped = { -1 };
        MaidModernPath[] origin = { null };
        helper.onEachTick("natural navigation switches and cancellation", () -> {
            f.check();
            var navigator = (MaidPathNavigator) f.maid.getNavigator();
            var path = navigator.getPath();
            if (!navigator.usesWaterNavigation() && path instanceof MaidModernPath modern && !modern.isFinished())
                origin[0] = modern;
            if (navigator.getManager()
                .mode() == MaidNavigationManager.Mode.WATER) entered[0] = true;
            if (!landed[0] && entered[0]
                && f.maid.posX > f.x + 9.3
                && f.maid.posY >= f.y + 2.9
                && f.maid.onGround
                && navigator.getManager()
                    .mode() == MaidNavigationManager.Mode.GROUND) {
                landed[0] = true;
                origin[0] = null;
                f.owner.setPosition(f.x + 1.5, f.y + 3, f.z + 3.5);
            }
            if (landed[0] && stopped[0] < 0
                && navigator.usesWaterNavigation()
                && path instanceof MaidModernPath modern
                && origin[0] != null
                && modern != origin[0]
                && !modern.isFinished()) {
                helper.assertTrue(
                    MaidPathOwnership.owns(modern, origin[0]),
                    "Mode handoff lost original cancellation identity");
                f.maid.setSitting(true);
                stopped[0] = f.maid.ticksExisted;
                System.out.println("TLMNH_NAV97_CANCEL_HANDOFF index=" + modern.index() + "/" + modern.size());
            }
            if (stopped[0] >= 0 && f.maid.ticksExisted > stopped[0] + 2)
                helper.assertTrue(navigator.noPath(), "Sitting failed to clear mode continuation");
        });
        helper.succeedWhen(() -> stopped[0] >= 0 && f.maid.ticksExisted >= stopped[0] + 10);
    }

    private static final class Fixture {

        final GameTestHelper helper;
        final EntityMaid maid;
        final EntityPlayerMP owner;
        final int x, y, z;
        final String kind;
        double previousX, previousY, previousZ;

        Fixture(GameTestHelper helper, String kind) {
            this.helper = helper;
            this.kind = kind;
            var p = helper.absolute(0, 0, 0);
            x = p.x();
            y = p.y();
            z = p.z();
            var server = MinecraftServer.getServer();
            boolean animals = server.getCanSpawnAnimals();
            server.setCanSpawnAnimals(true);
            helper.afterTest(() -> {
                server.setCanSpawnAnimals(animals);
                helper.assertEquals(animals, server.getCanSpawnAnimals(), "Animal policy not restored");
            });
            var chunks = new GameTestChunkLoader();
            helper.afterTest(chunks::releaseAll);
            try {
                chunks.forceChunksStrict(helper.getWorld(), x - 24, y, z - 24, x + 36, y + 6, z + 30);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            owner = new EntityPlayerMP(
                server,
                helper.getWorld(),
                new GameProfile(UUID.randomUUID(), "[QA-Traverse]"),
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
                for (int a = 0; a < 13; a++)
                    for (int b = 0; b < 7; b++) for (int c = 0; c < 7; c++) helper.setBlock(a, b, c, Blocks.air);
                helper.assertFalse(helper.getWorld().loadedEntityList.contains(maid), "Fixture actor retained");
                System.out.println("TLMNH_NAV97_CLEANUP_PASS kind=" + kind + " ticks=" + maid.ticksExisted);
            });
            for (int a = 0; a < 13; a++) for (int b = 0; b < 7; b++) for (int c = 0; c < 7; c++) {
                boolean solid = b == 0;
                if (kind.equals("doors")) solid |= (c == 2 || c == 4) && b > 0;
                if (kind.equals("ladder")) solid |= a >= 7 && b <= 3;
                // 入水后仍有至少五个水节点；水位低于岸面，宽池与窄槽竖直恢复分别记录
                if (kind.equals("shore")) solid |= (a <= 1 || a >= 9) && b <= 2 || (c == 0 || c == 6) && b > 0;
                helper.setBlock(
                    a,
                    b,
                    c,
                    solid ? Blocks.glass
                        : kind.equals("shore") && a >= 2 && a <= 8 && b < 3 ? Blocks.water : Blocks.air);
            }
            if (kind.equals("doors")) {
                helper.getWorld()
                    .setBlock(x + 4, y + 1, z + 3, Blocks.wooden_door, 0, 2);
                helper.getWorld()
                    .setBlock(x + 4, y + 2, z + 3, Blocks.wooden_door, 8, 2);
                helper.getWorld()
                    .setBlock(x + 6, y + 1, z + 3, Blocks.fence_gate, 1, 2);
            }
            if (kind.equals("ladder")) for (int b = 1; b <= 3; b++) helper.getWorld()
                .setBlock(x + 6, y + b, z + 3, Blocks.ladder, 4, 2);
            maid.getConfigManager()
                .setOpenDoor(true);
            maid.getConfigManager()
                .setOpenFenceGate(true);
            maid.getConfigManager()
                .setActiveClimbing(true);
            maid.setPickup(false);
            owner.setPosition(
                x + (kind.equals("shore") ? 12.5 : 11.5),
                y + (kind.equals("ladder") ? 4 : kind.equals("shore") ? 3 : 1),
                z + 3.5);
            owner.capabilities.disableDamage = true;
            helper.getWorld().playerEntities.add(owner);
            helper.assertTrue(maid.bindTo(owner.getUniqueID()), "Owner binding failed");
            helper.spawnEntity(maid, 1.5, kind.equals("shore") ? 3 : 1, 3.5);
            previousX = maid.posX;
            previousY = maid.posY;
            previousZ = maid.posZ;
        }

        boolean open(int a) {
            return (helper.getWorld()
                .getBlockMetadata(x + a, y + 1, z + 3) & 4) != 0;
        }

        void check() {
            helper.assertTrue(maid.isEntityAlive() && maid.getHealth() == 20, "Maid damaged during traversal");
            helper.assertFalse(
                maid.getBrainCoordinator()
                    .getBreathController()
                    .isActive(),
                "Low-air rescue masked ordinary route");
            double dx = maid.posX - previousX, dy = maid.posY - previousY, dz = maid.posZ - previousZ;
            helper.assertTrue(dx * dx + dy * dy + dz * dz < 1, "Discontinuous fixture movement");
            previousX = maid.posX;
            previousY = maid.posY;
            previousZ = maid.posZ;
            helper.assertTrue(
                helper.getWorld()
                    .func_147461_a(maid.boundingBox.contract(.001, .001, .001))
                    .isEmpty(),
                "Native body clipped into traversal fixture");
            if (maid.ticksExisted % 20 == 0) {
                var navigator = (MaidPathNavigator) maid.getNavigator();
                var path = navigator.getPath();
                var last = maid.getBrainCoordinator()
                    .getLastPathAttempt();
                System.out.println(
                    "TLMNH_NAV97_TRACE kind=" + kind
                        + " tick="
                        + maid.ticksExisted
                        + " xyz="
                        + maid.posX
                        + ","
                        + maid.posY
                        + ","
                        + maid.posZ
                        + " nav="
                        + navigator.usesWaterNavigation()
                        + " mode="
                        + navigator.getManager()
                            .mode()
                        + " swim="
                        + maid.getAquaticController()
                            .getManager()
                            .wantToSwim()
                        + " path="
                        + (path == null ? "null" : path.getCurrentPathIndex() + "/" + path.getCurrentPathLength())
                        + " diagnostic="
                        + (last == null ? "none" : last.diagnostic)
                        + " ground="
                        + maid.onGround
                        + " water="
                        + maid.isInWater()
                        + " yaw="
                        + maid.rotationYaw
                        + " forward="
                        + maid.moveForward
                        + " velocity="
                        + maid.motionX
                        + ","
                        + maid.motionY
                        + ","
                        + maid.motionZ);
                if (kind.equals("shore") && path instanceof MaidModernPath modern) {
                    var nodes = new StringBuilder("TLMNH_NAV97_NODES ");
                    for (var n : modern.result.nodes) nodes.append(n.x - x)
                        .append(',')
                        .append(n.y - y)
                        .append(',')
                        .append(n.z - z)
                        .append(':')
                        .append(n.type)
                        .append(' ');
                    System.out.println(nodes);
                }
            }
        }
    }
}
