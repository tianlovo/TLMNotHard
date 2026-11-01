package com.tianluoqaq.tlmnh.qa;

import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.entity.MaidEntityPose;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class AquaticLifecycleTests {

    private AquaticLifecycleTests() {}

    @GameTest(template = "aquatic_cell", timeoutTicks = 80)
    public static void liveSwimmingBody(GameTestHelper helper) {
        final MinecraftServer server = MinecraftServer.getServer();
        final boolean originalAnimals = server.getCanSpawnAnimals();
        // CI关闭动物开关会使旧WorldServer每tick删除EntityAnimal，先满足真实实体tick的前提
        helper.afterTest(() -> {
            server.setCanSpawnAnimals(originalAnimals);
            helper.assertEquals(originalAnimals, server.getCanSpawnAnimals(), "Animal policy restored");
            System.out.println("TLMNH_HQA_ANIMAL_POLICY_RESTORED value=" + originalAnimals);
        });
        server.setCanSpawnAnimals(true);
        final EntityMaid maid = new EntityMaid(helper.getWorld());
        helper.afterTest(() -> {
            maid.setDead();
            helper.getWorld()
                .removePlayerEntityDangerously(maid);
            for (int x = 0; x < 5; x++)
                for (int y = 0; y < 5; y++) for (int z = 0; z < 5; z++) helper.setBlock(x, y, z, Blocks.air);
            helper
                .assertFalse(helper.getWorld().loadedEntityList.contains(maid), "Fixture maid retained after cleanup");
            System.out.println("TLMNH_HQA_PILOT_CLEANUP_PASS nativeTicks=" + maid.ticksExisted);
        });
        for (int x = 0; x < 5; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 5; z++) helper
            .setBlock(x, y, z, x == 0 || x == 4 || y == 0 || y == 4 || z == 0 || z == 4 ? Blocks.glass : Blocks.water);
        helper.spawnEntity(maid, helper.pos("maid"));
        helper.onEachTick(
            "living fixture",
            () -> helper.assertTrue(
                maid.isEntityAlive(),
                "Fixture maid dead=" + maid.isDead
                    + " health="
                    + maid.getHealth()
                    + " nativeTicks="
                    + maid.ticksExisted));
        if (Boolean.getBoolean("tlmnh.qa.injectPoseFault"))
            helper.onEachTick("inject legacy dimension overwrite", () -> {
                if (maid.getEntityPose() == MaidEntityPose.SWIMMING) {
                    maid.height = 1.5F;
                    maid.boundingBox.maxY = maid.boundingBox.minY + 1.5;
                }
            });
        helper.onEachTick("live pose dimensions", () -> {
            MaidEntityPose pose = maid.getEntityPose();
            helper.assertTrue(
                Math.abs(maid.height - pose.height) < 1e-6,
                "Pose " + pose + " expected height=" + pose.height + " actual=" + maid.height);
            helper.assertTrue(
                Math.abs(maid.boundingBox.maxY - maid.boundingBox.minY - pose.height) < 1e-6,
                "Physical collision height differs from " + pose);
        });
        helper.startSequence()
            .thenWaitUntil("native world enters swimming", () -> {
                helper.assertTrue(maid.ticksExisted > 0, "No native entity tick observed");
                helper.assertEquals(MaidEntityPose.SWIMMING, maid.getEntityPose(), "Expected natural swimming");
            })
            .thenExecute("leave water and suspend ordinary behavior", () -> {
                maid.getBrainCoordinator()
                    .setSuspended(true);
                for (int x = 0; x < 5; x++)
                    for (int y = 1; y < 5; y++) for (int z = 0; z < 5; z++) helper.setBlock(x, y, z, Blocks.air);
                final com.gtnewhorizons.horizonqa.api.TestPos p = helper.absolute("maid");
                maid.setPosition(p.x(), p.y(), p.z());
            })
            .thenWaitUntil(
                "standing restored",
                () -> helper.assertEquals(MaidEntityPose.STANDING, maid.getEntityPose(), "Standing"))
            .thenExecute("controlled sleep request", () -> maid.setPose(MaidPose.SLEEPING, true))
            .thenWaitUntil(
                "sleeping body",
                () -> helper.assertEquals(MaidEntityPose.SLEEPING, maid.getEntityPose(), "Sleeping"))
            .thenExecute("controlled wake request", () -> maid.setPose(MaidPose.SLEEPING, false))
            .thenWaitUntil(
                "awake body",
                () -> helper.assertEquals(MaidEntityPose.STANDING, maid.getEntityPose(), "Awake"))
            .thenSucceed();
    }
}
