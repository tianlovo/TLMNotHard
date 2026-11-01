package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.projectile.EntitySnowball;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidCombatActor;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ModernLeisureTests {

    private ModernLeisureTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 650, batch = "modern_leisure_random")
    public static void naturalRandomLookAndWalkUseNativeMotionThenRideWorkOnlyLooks(GameTestHelper helper) {
        var f = fixture(helper, false);
        int[] phase = { 0 }, changed = { -1 };
        long[] walks = { -1 };
        boolean[] moved = { false }, done = { false };
        helper.onEachTick("actual weighted random goals, native path movement and ride-only look", () -> {
            f.check();
            var actor = f.core.getActor();
            if (f.maid.ticksExisted == 20 || f.maid.ticksExisted % 100 == 0) System.out.println(
                "TLMNH_LEISURE111_RANDOM_TRACE age=" + f.maid.ticksExisted
                    + " activity="
                    + f.core.getActivities()
                        .active()
                    + " eligible="
                    + actor.randomGateEligible()
                    + " breath="
                    + actor.isGoingToBreath()
                    + " moving="
                    + actor.canBrainMoving()
                    + " looks="
                    + actor.randomLookSelections()
                    + " walks="
                    + actor.randomWalkSelections()
                    + " target="
                    + actor.peekWalkTarget());
            double dx = f.maid.posX - (f.x + 1.5), dz = f.maid.posZ - (f.z + 4.5);
            if (dx * dx + dz * dz > .1) moved[0] = true;
            int age = f.maid.ticksExisted;
            if (phase[0] == 0 && moved[0]
                && actor.randomLookSelections() > 0
                && actor.randomWalkSelections() > 0
                && f.navigation.searchCount() > 0) {
                f.maid.setSitting(true);
                changed[0] = age;
                phase[0] = 1;
            } else if (phase[0] == 1 && age >= changed[0] + 30) {
                walks[0] = actor.randomWalkSelections();
                changed[0] = age;
                phase[0] = 2;
            } else if (phase[0] == 2 && age >= changed[0] + 65) {
                helper.assertTrue(
                    actor.randomWalkSelections() == walks[0] && f.navigation.noPath(),
                    "Ride work created random walking or retained navigation");
                done[0] = true;
                System.out.println(
                    "TLMNH_LEISURE111_RANDOM_PASS age=" + age
                        + " looks="
                        + actor.randomLookSelections()
                        + " walks="
                        + walks[0]);
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 260, batch = "modern_leisure_snow_hands")
    public static void naturalColdSnowProducesOwnerlessFlightAndKeepsMainThenOffhandSnowball(GameTestHelper helper) {
        var f = fixture(helper, true);
        var hook = hook(helper, f);
        int[] phase = { 0 }, changed = { -1 };
        long[] firstThrows = { 0 };
        boolean[] done = { false };
        helper.onEachTick("cold biome and real snow layer, natural main/offhand snowball use", () -> {
            f.check();
            int age = f.maid.ticksExisted;
            var actor = f.core.getActor();
            helper.assertTrue(hook.ownerless, "Leisure snowball gained an attacker attribution");
            if (phase[0] == 0 && actor.snowballThrows() > 0 && hook.moved()) {
                helper.assertTrue(
                    f.maid.getHeldItem() != null && f.maid.getHeldItem()
                        .getItem() == Items.snowball
                        && f.maid.getHeldItem().stackSize == 1
                        && f.maid.getSnowballAnimation()
                            .sequence() > 0,
                    "Main-hand pickup did not preserve one unconsumed snowball and animation trigger");
                firstThrows[0] = actor.snowballThrows();
                // 明确的后续装备/任务输入，不把设置槽位当成原GUI自动收纳已验
                f.maid.setTask(MaidTask.ATTACK);
                f.maid.setCurrentItemOrArmor(0, new ItemStack(Items.feather));
                f.maid.setOffhandItem(null);
                changed[0] = age;
                phase[0] = 1;
            } else if (phase[0] == 1 && age > changed[0]) {
                helper.assertTrue(
                    f.maid.getAttackTarget() == null,
                    "Old snow behavior kept owner target after task switch");
                f.maid.setTask(MaidTask.IDLE);
                phase[0] = 2;
            } else if (phase[0] == 2 && actor.snowballThrows() > firstThrows[0]) {
                helper.assertTrue(
                    f.maid.getHeldItem()
                        .getItem() == Items.feather && f.maid.getOffhandItem() != null
                        && f.maid.getOffhandItem()
                            .getItem() == Items.snowball
                        && f.maid.getOffhandItem().stackSize == 1
                        && f.maid.hasPose(MaidPose.SWING_OFFHAND),
                    "Offhand snowball replaced main item, was consumed or used wrong swing hand");
                helper.assertTrue(
                    f.owner.getHealth() == 20 && f.owner.getAITarget() == null,
                    "Ownerless snowball damaged owner or created revenge attribution");
                done[0] = true;
                System.out.println(
                    "TLMNH_LEISURE111_SNOW_HANDS_PASS age=" + age
                        + " throws="
                        + actor.snowballThrows()
                        + " spawned="
                        + hook.balls.size());
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 240, batch = "modern_leisure_snow_cancel")
    public static void realSnowballSpawnRevocationRejectsOldFrameThenFreshResumeThrows(GameTestHelper helper) {
        var f = fixture(helper, true);
        var hook = hook(helper, f);
        hook.revoke = true;
        MaidCombatActor.Target[] old = { null };
        ItemStack[] original = { null };
        int[] phase = { 0 }, changed = { -1 };
        boolean[] done = { false };
        helper.onEachTick("actual EntityJoinWorld callback revokes old snowball behavior", () -> {
            var actor = f.core.getActor();
            int age = f.maid.ticksExisted;
            if (phase[0] == 0 && f.maid.getAttackTarget() != null && hook.balls.isEmpty()) {
                old[0] = actor.attackTarget();
                original[0] = f.maid.getHeldItem();
            }
            if (phase[0] == 0 && !hook.balls.isEmpty()) {
                helper.assertTrue(
                    old[0] != null && actor.snowballThrows() == 0
                        && hook.balls.get(0).isDead
                        && f.maid.getAttackTarget() == null
                        && original[0] == f.maid.getHeldItem()
                        && original[0].stackSize == 1,
                    "Revoked spawn left live projectile, stale target or consumed item");
                changed[0] = age;
                phase[0] = 1;
            } else if (phase[0] == 1 && age >= changed[0] + 3) {
                hook.revoke = false;
                f.maid.getBrainCoordinator()
                    .setSuspended(false);
                changed[0] = age;
                phase[0] = 2;
            } else if (phase[0] == 2 && age > changed[0]) {
                boolean rejected = false;
                try {
                    actor.throwSnowballAt(old[0]);
                } catch (IllegalArgumentException | IllegalStateException expected) {
                    rejected = true;
                }
                helper.assertTrue(
                    rejected && hook.balls.size() == 1,
                    "Old snow target obtained a fresh frame capability");
                phase[0] = 3;
            } else if (phase[0] == 3 && actor.snowballThrows() > 0 && hook.moved()) {
                f.check();
                helper.assertTrue(
                    f.maid.getHeldItem() == original[0] && original[0].stackSize == 1 && hook.ownerless,
                    "Fresh resume lost original unconsumed snowball or ownerless source");
                done[0] = true;
                System.out.println("TLMNH_LEISURE111_CANCEL_PASS age=" + age + " spawned=" + hook.balls.size());
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper, boolean snowy) {
        var f = new ModernCoreTests.Fixture(helper, false, 1, 16);
        f.maid.setSchedule(MaidSchedule.ALL);
        f.owner.setPosition(f.x + 3.5, f.y + 1, f.z + 4.5);
        var ownerChunk = helper.getWorld()
            .getChunkFromBlockCoords((int) Math.floor(f.owner.posX), (int) Math.floor(f.owner.posZ));
        ownerChunk.addEntity(f.owner);
        helper.afterTest(() -> ownerChunk.removeEntity(f.owner));
        for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) {
            helper.setBlock(x, 0, z, Blocks.stone);
            if (snowy) helper.setBlock(x, 1, z, Blocks.snow_layer);
        }
        if (snowy) {
            for (int x = f.x >> 4; x <= (f.x + 20) >> 4; x++) for (int z = f.z >> 4; z <= (f.z + 8) >> 4; z++) {
                var chunk = helper.getWorld()
                    .getChunkFromChunkCoords(x, z);
                short[] biomes;
                try {
                    biomes = (short[]) chunk.getClass()
                        .getMethod("getBiomeShortArray")
                        .invoke(chunk);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Formal EndlessIDs chunk biome hook unavailable", failure);
                }
                short[] previous = biomes.clone();
                java.util.Arrays.fill(biomes, (short) BiomeGenBase.icePlains.biomeID);
                helper.afterTest(() -> System.arraycopy(previous, 0, biomes, 0, previous.length));
            }
            helper.assertTrue(
                helper.getWorld()
                    .getBiomeGenForCoords(f.x + 1, f.z + 4)
                    .getFloatTemperature(f.x + 1, f.y + 1, f.z + 4) < .15F,
                "Native cold biome input did not apply");
        }
        return f;
    }

    private static BallHook hook(GameTestHelper helper, ModernCoreTests.Fixture f) {
        var hook = new BallHook(f);
        MinecraftForge.EVENT_BUS.register(hook);
        helper.afterTest(() -> {
            MinecraftForge.EVENT_BUS.unregister(hook);
            for (var ball : hook.balls) ball.setDead();
        });
        return hook;
    }

    public static final class BallHook {

        private final ModernCoreTests.Fixture f;
        final List<EntitySnowball> balls = new ArrayList<>();
        final List<double[]> starts = new ArrayList<>();
        boolean revoke, ownerless = true;

        BallHook(ModernCoreTests.Fixture f) {
            this.f = f;
        }

        @SubscribeEvent
        public void joining(EntityJoinWorldEvent event) {
            if (!(event.entity instanceof EntitySnowball ball) || event.world != f.maid.worldObj
                || ball.getDistanceSqToEntity(f.maid) > .25) return;
            balls.add(ball);
            starts.add(new double[] { ball.posX, ball.posY, ball.posZ });
            ownerless &= ball.getThrower() == null;
            if (revoke) f.maid.getBrainCoordinator()
                .setSuspended(true);
        }

        boolean moved() {
            for (int i = 0; i < balls.size(); i++) {
                var ball = balls.get(i);
                var pos = starts.get(i);
                if (ball.ticksExisted > 0 && ball.getDistanceSq(pos[0], pos[1], pos[2]) > .01) return true;
            }
            return false;
        }
    }
}
