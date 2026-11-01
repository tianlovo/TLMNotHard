package com.tianluoqaq.tlmnh.qa;

import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidVisibleLivingEntities;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.config.MaidHomeConfig;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ModernPerceptionTests {

    private ModernPerceptionTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 90, batch = "modern_perception_cache")
    public static void realWallResultsStayCachedUntilScanAndRetiredPolicyCannotRead(GameTestHelper helper) {
        var f = fixture(helper);
        var near = spawn(helper, new EntityPig(helper.getWorld()), 4.5, 2.5);
        var blocked = spawn(helper, new EntityPig(helper.getWorld()), 4.5, 6.5);
        wall(helper, 5, 7, true);
        int[] phase = { 0 }, started = { 0 };
        long[] scan = { 0 };
        MaidVisibleLivingEntities[] old = { null };
        boolean[] done = { false };
        helper.onEachTick("positive and negative visibility cache across natural ticks", () -> {
            f.check();
            var p = f.core.getPerception();
            var visible = p.visible();
            if (visible == null) return;
            if (phase[0] == 0) {
                helper.assertTrue(
                    p.nearby()
                        .contains(near)
                        && p.nearby()
                            .contains(blocked),
                    "Wall filtered raw nearby memory");
                helper.assertTrue(
                    visible.contains(near) && !visible.contains(blocked),
                    "Initial real wall sight was incorrect");
                old[0] = visible;
                scan[0] = p.livingScanCount();
                started[0] = f.maid.ticksExisted;
                wall(helper, 5, 7, false);
                wall(helper, 1, 4, true);
                phase[0] = 1;
            } else if (p.livingScanCount() == scan[0]) {
                helper.assertTrue(
                    visible == old[0] && visible.contains(near) && !visible.contains(blocked),
                    "Positive or negative sight result was recalculated before the next scan");
            } else {
                helper.assertTrue(f.maid.ticksExisted - started[0] == 20, "Living scan cadence drifted");
                helper.assertTrue(
                    !visible.contains(near) && visible.contains(blocked),
                    "New snapshot did not observe changed walls");
                boolean retired = false;
                try {
                    old[0].contains(near);
                } catch (IllegalStateException expected) {
                    retired = true;
                }
                helper.assertTrue(retired, "Old scan view remained accessible");
                f.maid.getBrainCoordinator()
                    .peekPolicyRuntime()
                    .select(MaidBrainProfile.LEGACY_PROTOTYPE);
                retired = false;
                try {
                    visible.contains(blocked);
                } catch (IllegalStateException expected) {
                    retired = true;
                }
                helper.assertTrue(retired, "Retired policy retained visible memory");
                done[0] = true;
                System.out.println("TLMNH_PERCEPTION102_CACHE_PASS tick=" + f.maid.ticksExisted);
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 160, batch = "modern_perception_hostile")
    public static void hostileMemoryUsesCreeperEightBlockBoundaryAndInvisibleArmor(GameTestHelper helper) {
        var f = fixture(helper);
        MaidHomeConfig.workRange = 20;
        var zombie = spawn(helper, new EntityZombie(helper.getWorld()), 3.5, 4.5);
        var hidden = spawn(helper, new EntityCreeper(helper.getWorld()), 5.5, 4.5);
        hidden.addPotionEffect(new PotionEffect(Potion.invisibility.id, 600, 0));
        var visibleCreeper = spawn(helper, new EntityCreeper(helper.getWorld()), 8.5, 4.5);
        var boundary = spawn(helper, new EntityCreeper(helper.getWorld()), 9.5, 4.5);
        var sixteen = spawn(helper, new EntityPig(helper.getWorld()), 17.5, 4.5);
        var beyond = spawn(helper, new EntityPig(helper.getWorld()), 17.6, 6.5);
        int[] phase = { 0 };
        long[] living = { 0 }, hostile = { 0 };
        boolean[] done = { false };
        helper.onEachTick("actual living entities, independent sensor cadence and strict hostile radius", () -> {
            f.check();
            var p = f.core.getPerception();
            if (p.visible() == null || p.livingScanCount() < 2 || p.hostileScanCount() < 2) return;
            if (phase[0] == 0) {
                helper.assertTrue(
                    hidden.isPotionActive(Potion.invisibility) && hidden.isInvisible(),
                    "Real invisibility effect did not reach the target entity");
                var actual = p.nearestHostile();
                System.out.println(
                    "TLMNH_PERCEPTION102_HOSTILE_INPUT hidden=" + hidden.isInvisible()
                        + " selected="
                        + (actual == null ? "null" : actual.getEntityId())
                        + " expected="
                        + visibleCreeper.getEntityId()
                        + " boundaryDistanceSq="
                        + boundary.getDistanceSqToEntity(f.maid));
                helper.assertTrue(
                    p.nearestHostile() == visibleCreeper,
                    "Non-creeper, invisible or boundary target replaced nearest visible hostile");
                helper.assertTrue(
                    p.nearby()
                        .contains(beyond)
                        && p.visible()
                            .contains(sixteen)
                        && !p.visible()
                            .contains(beyond),
                    "Raw search radius and Sensor's sixteen-block targetability were conflated");
                helper.assertTrue(
                    !p.visible()
                        .contains(hidden),
                    "Unarmored invisible creeper was seen at four blocks");
                hidden.setCurrentItemOrArmor(1, new ItemStack(Items.iron_boots));
                hidden.setCurrentItemOrArmor(2, new ItemStack(Items.iron_leggings));
                hidden.setCurrentItemOrArmor(3, new ItemStack(Items.iron_chestplate));
                hidden.setCurrentItemOrArmor(4, new ItemStack(Items.iron_helmet));
                living[0] = p.livingScanCount();
                hostile[0] = p.hostileScanCount();
                phase[0] = 1;
            } else if (phase[0] == 1 && p.livingScanCount() > living[0] && p.hostileScanCount() > hostile[0] + 1) {
                helper.assertTrue(
                    p.visible()
                        .contains(hidden) && p.nearestHostile() == hidden,
                    "Armor visibility or nearest-hostile refresh failed");
                hidden.setDead();
                visibleCreeper.setDead();
                living[0] = p.livingScanCount();
                hostile[0] = p.hostileScanCount();
                phase[0] = 2;
            } else if (phase[0] == 2 && p.livingScanCount() > living[0] && p.hostileScanCount() > hostile[0] + 1) {
                helper.assertTrue(
                    p.visible()
                        .contains(boundary) && p.nearestHostile() == null,
                    "Exactly eight blocks or an ordinary zombie entered the hostile memory");
                helper.assertTrue(
                    !p.nearby()
                        .contains(hidden) && p.nearby()
                            .contains(zombie),
                    "Dead living entries were retained after a scan");
                done[0] = true;
                System.out.println("TLMNH_PERCEPTION102_HOSTILE_PASS tick=" + f.maid.ticksExisted);
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false);
        f.owner.setPosition(f.x + 1.5, f.y + 1, f.z + 1.5);
        return f;
    }

    private static <T extends EntityLiving> T spawn(GameTestHelper helper, T entity, double x, double z) {
        entity.tasks.taskEntries.clear();
        entity.targetTasks.taskEntries.clear();
        entity.func_110163_bv();
        helper.spawnEntity(entity, x, 1, z);
        helper.afterTest(() -> {
            entity.setDead();
            helper.getWorld()
                .removePlayerEntityDangerously(entity);
        });
        return entity;
    }

    private static void wall(GameTestHelper helper, int fromZ, int toZ, boolean solid) {
        for (int y = 1; y <= 3; y++)
            for (int z = fromZ; z <= toZ; z++) helper.setBlock(3, y, z, solid ? Blocks.stone : Blocks.air);
    }
}
