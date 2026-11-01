package com.tianluoqaq.tlmnh.qa;

import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.MaidWaterPhysics;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class WaterTravelTests {

    private WaterTravelTests() {}

    @GameTest(template = "aquatic_cell", timeoutTicks = 220, batch = "water_float")
    public static void idleMaidSurfacesBeforeLowAir(GameTestHelper helper) {
        EntityMaid maid = fixture(helper, true, false, false);
        final double initialY = maid.posY;
        helper.onEachTick("ordinary passive travel", () -> {
            helper.assertTrue(maid.isEntityAlive(), "Maid died in open water");
            helper.assertTrue(maid.getAir() > 100, "Low-air rescue would mask passive travel");
            helper.assertFalse(
                maid.getBrainCoordinator()
                    .getBreathController()
                    .isActive(),
                "Unexpected emergency breath route");
            helper.assertFalse(
                maid.getAquaticController()
                    .getManager()
                    .wantToSwim(),
                "Idle no-path state latched active swimming");
        });
        helper.succeedWhen(() -> {
            return maid.ticksExisted > 1 && maid.posY > initialY + 1.3
                && !maid.isInsideOfMaterial(Material.water)
                && maid.getAir() == 300;
        });
    }

    @GameTest(template = "aquatic_cell", timeoutTicks = 60, batch = "water_sealed")
    public static void sealedWaterNeverClipsOrCreatesAir(GameTestHelper helper) {
        EntityMaid maid = fixture(helper, true, true, false);
        final double roof = helper.absolute(0, 4, 0)
            .y();
        helper.onEachTick("solid ceiling and submerged air", () -> {
            helper.assertTrue(maid.isEntityAlive(), "Maid died before sealed observation ended");
            helper.assertTrue(maid.boundingBox.maxY <= roof + 1e-6, "Maid clipped through solid ceiling");
            if (maid.ticksExisted > 10)
                helper.assertTrue(maid.boundingBox.maxY >= roof - .001, "Maid never reached the ceiling");
            helper.assertTrue(maid.getAir() < 300, "Sealed pool created air");
        });
        helper.succeedAtTimeout();
    }

    @GameTest(template = "aquatic_cell", timeoutTicks = 140, batch = "water_shore")
    public static void controlledWaterInputClimbsRealShore(GameTestHelper helper) {
        EntityMaid maid = fixture(helper, false, false, true);
        final double shoreX = helper.absolute(3, 0, 0)
            .x();
        final double shoreY = helper.absolute(0, 2, 0)
            .y();
        helper.onEachTick("controlled input with native collision", () -> {
            if (maid.handleWaterMovement()) MaidWaterPhysics.travel(maid, 0, 1, true);
            else maid.moveEntityWithHeading(0, 1);
            helper.assertTrue(maid.isEntityAlive(), "Controlled actor died");
            helper.assertTrue(
                helper.getWorld()
                    .getCollidingBoundingBoxes(maid, maid.boundingBox.contract(.001, .001, .001))
                    .isEmpty(),
                "Native body clipped into shore");
        });
        helper.succeedWhen(() -> { return maid.posX > shoreX + .3 && maid.posY >= shoreY - 1e-6; });
    }

    private static EntityMaid fixture(GameTestHelper helper, boolean registered, boolean roof, boolean shore) {
        MinecraftServer server = MinecraftServer.getServer();
        boolean animals = server.getCanSpawnAnimals();
        helper.afterTest(() -> {
            server.setCanSpawnAnimals(animals);
            helper.assertEquals(animals, server.getCanSpawnAnimals(), "Animal policy restored");
        });
        server.setCanSpawnAnimals(true);
        EntityMaid maid = new EntityMaid(helper.getWorld());
        helper.afterTest(() -> {
            maid.setDead();
            helper.getWorld()
                .removePlayerEntityDangerously(maid);
            for (int x = 0; x < 5; x++)
                for (int y = 0; y < 5; y++) for (int z = 0; z < 5; z++) helper.setBlock(x, y, z, Blocks.air);
            helper.assertFalse(helper.getWorld().loadedEntityList.contains(maid), "Fixture entity retained");
            System.out
                .println("TLMNH_WATER95_CLEANUP_PASS registered=" + registered + " nativeTicks=" + maid.ticksExisted);
        });
        for (int x = 0; x < 5; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 5; z++) {
            boolean wall = shore ? z == 0 || z == 4 || x == 0 : x == 0 || x == 4 || z == 0 || z == 4;
            helper.setBlock(
                x,
                y,
                z,
                y == 0 || wall || roof && y == 4 || shore && y == 1 && x >= 3 ? Blocks.glass
                    : y <= (shore ? 1 : 3) ? Blocks.water : Blocks.air);
        }
        if (registered) helper.spawnEntity(maid, 2.5, roof ? 2.45 : 1.05, 2.5);
        else {
            var p = helper.absolute(0, 0, 0);
            maid.setPosition(p.x() + 2.4, p.y() + 1.05, p.z() + 2.5);
            maid.rotationYaw = -90;
            maid.setAIMoveSpeed(.3F);
        }
        return maid;
    }
}
