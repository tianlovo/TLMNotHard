package com.tianluoqaq.tlmnh.qa;

import net.minecraft.init.Blocks;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class MaidStepPhysicsTests {

    private MaidStepPhysicsTests() {}

    /** 调实际原生碰撞，不手动推进Brain，也不将此局部物理场景当成自然AI对照 */
    @GameTest(template = "modern_core_cell", timeoutTicks = 5, batch = "step_collision")
    public static void sourceDefaultStepsOntoBedWithoutClimbingAFullBlock(GameTestHelper helper) {
        for (int x = 0; x < 7; x++) for (int y = 0; y < 4; y++)
            for (int z = 0; z < 5; z++) helper.setBlock(x, y, z, y == 0 ? Blocks.stone : Blocks.air);
        var origin = helper.absolute(0, 0, 0);
        var world = helper.getWorld();
        world.setBlock(origin.x() + 3, origin.y() + 1, origin.z() + 2, Blocks.bed, 0, 2);
        var maid = new EntityMaid(world);
        var oldDefault = new EntityMaid(world);
        var fullBlock = new EntityMaid(world);
        helper.afterTest(maid::setDead);
        helper.afterTest(oldDefault::setDead);
        helper.afterTest(fullBlock::setDead);
        helper.assertTrue(maid.stepHeight == .6F, "Original captured step height was not the entity default");
        oldDefault.stepHeight = .5F;
        for (var actor : new EntityMaid[] { maid, oldDefault }) {
            actor.setPosition(origin.x() + 2.5, origin.y() + 1, origin.z() + 2.5);
            actor.onGround = true;
            actor.moveEntity(1, -.08, 0);
        }
        helper.assertTrue(
            maid.posX > origin.x() + 3.4 && Math.abs(maid.boundingBox.minY - (origin.y() + 1.5625)) < 1.0e-6,
            "Native movement did not step onto the actual bed collision");
        helper.assertTrue(
            oldDefault.posX < origin.x() + 2.71 && Math.abs(oldDefault.boundingBox.minY - origin.y() - 1) < 1.0e-6,
            "Old default control did not reproduce the blocked half-height boundary");
        world.setBlock(origin.x() + 3, origin.y() + 1, origin.z() + 2, Blocks.stone, 0, 2);
        fullBlock.setPosition(origin.x() + 2.5, origin.y() + 1, origin.z() + 2.5);
        fullBlock.onGround = true;
        fullBlock.moveEntity(1, -.08, 0);
        helper.assertTrue(
            fullBlock.posX < origin.x() + 2.71 && Math.abs(fullBlock.boundingBox.minY - origin.y() - 1) < 1.0e-6,
            "Source step height bypassed a full block");
        System.out.println("TLMNH_STEP_PHYSICS_PASS source=.6 old=.5 bed=.5625 full_block=blocked");
        helper.succeedWhen(() -> true);
    }
}
