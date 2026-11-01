package com.tianluoqaq.tlmnh.qa;

import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.DamageSource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class EnhancedPanicTests {

    private EnhancedPanicTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 120, batch = "enhanced_panic_hurt")
    public static void cancelledHurtKeepsMealActualHurtPausesWithoutInventedRouteAndExpires(GameTestHelper helper) {
        var f = fixture(helper, false);
        var sword = new ItemStack(Items.iron_sword);
        var food = new ItemStack(Items.golden_apple, 2, 1);
        f.maid.setCurrentItemOrArmor(0, sword);
        f.maid.setHealth(10);
        f.maid.getMaidInventory()
            .setInventorySlotContents(0, food.copy());
        var core = select(helper, f);
        var hook = new CancelHurt(f);
        MinecraftForge.EVENT_BUS.register(hook);
        helper.afterTest(() -> MinecraftForge.EVENT_BUS.unregister(hook));
        int[] phase = { 0 }, changed = { -1 };
        long[] hurtAt = { -1 };
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            int age = f.maid.ticksExisted;
            if (phase[0] == 0) {
                if (age < 4 || !f.maid.getMealController()
                    .isEating()) return false;
                hook.cancel = true;
                helper.assertFalse(f.maid.attackEntityFrom(DamageSource.generic, 1), "Cancelled damage was accepted");
                hook.cancel = false;
                changed[0] = age;
                phase[0] = 1;
                return false;
            }
            if (age <= changed[0]) return false;
            if (phase[0] == 1) {
                helper.assertTrue(
                    core.selectedKind() == Kind.HEAL && f.maid.getMealController()
                        .isEating() && f.maid.getMaidLastDamageSource() == null && hook.cancelled == 1,
                    "Cancelled real event created panic or stopped meal");
                helper.assertTrue(f.maid.attackEntityFrom(DamageSource.generic, 1), "Actual hurt was not accepted");
                hurtAt[0] = f.maid.worldObj.getTotalWorldTime();
                changed[0] = age;
                phase[0] = 2;
                return false;
            }
            long elapsed = f.maid.worldObj.getTotalWorldTime() - hurtAt[0];
            if (elapsed <= 40) {
                helper.assertTrue(
                    core.selectedKind() == Kind.PANIC && core.panic()
                        .threat() == null
                        && core.panic()
                            .destination() == null
                        && core.actor()
                            .peekWalkTarget() == null
                        && f.navigation.noPath()
                        && !f.maid.getMealController()
                            .isEating()
                        && f.maid.getItemUseState()
                            .peek() == null
                        && f.maid.getHeldItem() == sword
                        && ItemStack.areItemStacksEqual(
                            food,
                            f.maid.getMaidInventory()
                                .getStackInSlot(0)),
                    "Accepted hurt failed to pause/restore resources or invented a flee route");
                return false;
            }
            helper.assertTrue(
                core.selectedKind() == Kind.HEAL && f.maid.getMealController()
                    .isEating() && f.maid.getMaidLastDamageSource() == null,
                "Expired actual damage did not resume care");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "enhanced_panic_flee")
    public static void visibleCreeperLeavesSitMovesCancelsResumesAndHiddenCacheCannotContinue(GameTestHelper helper) {
        var f = fixture(helper, false);
        helper.assertFalse(
            f.navigation.isStableDestination(f.x + 10, f.y + 1, f.z + 4),
            "Glass fixture floor unexpectedly qualified for native land sampling");
        f.maid.setPosition(f.x + 10.5, f.y + 1, f.z + 4.5);
        f.owner.setPosition(f.x + 10.5, f.y + 1, f.z + 1.5);
        f.maid.setSitting(true);
        var core = select(helper, f);
        var creeper = creeper(helper, 14.5, 1);
        int[] phase = { 0 }, changed = { -1 };
        double[] beganX = { f.maid.posX };
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            int age = f.maid.ticksExisted;
            if (age <= 5 || age % 20 == 0) System.out.println(
                "TLMNH_N_PANIC_TRACE age=" + age
                    + " phase="
                    + phase[0]
                    + " kind="
                    + core.selectedKind()
                    + " sitting="
                    + f.maid.isSitting()
                    + " x="
                    + f.maid.posX
                    + " z="
                    + f.maid.posZ
                    + " distance="
                    + f.maid.getDistanceSqToEntity(creeper)
                    + " visible="
                    + f.maid.canEntityBeSeen(creeper)
                    + " path="
                    + !f.navigation.noPath()
                    + " outcome="
                    + core.navigation()
                        .outcome()
                    + " destination="
                    + core.panic()
                        .destination()
                    + " samples="
                    + core.panic()
                        .samples()
                    + " transitions="
                    + core.transitions());
            if (phase[0] == 0 || phase[0] == 2) {
                var point = core.panic()
                    .destination();
                if (core.selectedKind() != Kind.PANIC || point == null || f.navigation.noPath()) return false;
                helper.assertTrue(
                    !f.maid.isSitting() && core.panic()
                        .threat() == creeper && point.x + .5 < f.maid.posX,
                    "Visible threat did not select an actual away route");
                if (phase[0] == 0) {
                    if (beganX[0] - f.maid.posX < .3) return false;
                    var old = core.actor()
                        .peekWalkTarget();
                    f.maid.getBrainCoordinator()
                        .setSuspended(true);
                    boolean expired = false;
                    try {
                        old.currentBlockPosition();
                    } catch (IllegalStateException expected) {
                        expired = true;
                    }
                    helper.assertTrue(
                        expired && f.navigation.noPath()
                            && core.panic()
                                .threat() == null,
                        "Suspension kept a route, threat or old capability");
                    changed[0] = age;
                    phase[0] = 1;
                } else {
                    for (int y = 1; y <= 6; y++) for (int z = 1; z < 8; z++) helper.setBlock(12, y, z, Blocks.stone);
                    helper.assertFalse(f.maid.canEntityBeSeen(creeper), "Opaque wall did not hide real threat");
                    f.owner.setPosition(f.maid.posX, f.maid.posY, f.maid.posZ);
                    changed[0] = age;
                    phase[0] = 3;
                }
                return false;
            }
            if (phase[0] == 1) {
                helper.assertTrue(f.navigation.noPath() && !f.maid.isSitting(), "Suspended panic moved/restored sit");
                if (age <= changed[0] + 3) return false;
                f.maid.getBrainCoordinator()
                    .setSuspended(false);
                phase[0] = 2;
                return false;
            }
            if (age <= changed[0]) return false;
            helper.assertTrue(
                core.selectedKind() != Kind.PANIC && core.panic()
                    .threat() == null
                    && core.panic()
                        .destination() == null
                    && f.navigation.noPath(),
                "Hidden cached threat retained panic or a live route");
            return age >= changed[0] + 3;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 200, batch = "enhanced_panic_support")
    public static void collisionLandingRejectsUnsafeTerrainAndChangedDestinationRetiresItsRoute(GameTestHelper helper) {
        var f = fixture(helper, false);
        f.maid.setPosition(f.x + 10.5, f.y + 1, f.z + 4.5);
        f.owner.setPosition(f.x + 10.5, f.y + 1, f.z + 1.5);
        var core = select(helper, f);
        creeper(helper, 14.5, 1);
        Point[] removed = { null };
        int[] changed = { -1 };
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            if (removed[0] == null) {
                var target = core.panic()
                    .destination();
                if (core.selectedKind() != Kind.PANIC || target == null
                    || f.navigation.noPath()
                    || f.maid.getDistanceSq(target.x + .5, target.y, target.z + .5) < 4) return false;
                var point = new Point(f.x + 3, f.y + 1, f.z + 4);
                helper.assertTrue(
                    core.actor()
                        .isEnhancedLandDestination(point),
                    "Actual glass support was rejected");
                helper.assertFalse(
                    f.navigation.isStableDestination(point.x, point.y, point.z),
                    "F opaque-support contract changed");
                helper.setBlock(3, 0, 4, Blocks.stone_slab);
                helper.assertTrue(
                    core.actor()
                        .isEnhancedLandDestination(point),
                    "Actual half-slab support was rejected");
                helper.setBlock(3, 0, 4, Blocks.air);
                helper.assertFalse(
                    core.actor()
                        .isEnhancedLandDestination(point),
                    "Void provided false support");
                helper.setBlock(3, 0, 4, Blocks.glass);
                helper.setBlock(3, 1, 4, Blocks.glass);
                helper.assertFalse(
                    core.actor()
                        .isEnhancedLandDestination(point),
                    "Blocked body was admitted");
                helper.setBlock(3, 1, 4, Blocks.water);
                helper.assertFalse(
                    core.actor()
                        .isEnhancedLandDestination(point),
                    "Water was admitted as dry landing");
                helper.setBlock(3, 0, 4, Blocks.stone);
                helper.setBlock(3, 1, 4, Blocks.fire);
                helper.assertTrue(
                    helper.getWorld()
                        .getBlock(point.x, point.y, point.z) == Blocks.fire,
                    "Hazard fixture did not retain actual fire on solid support");
                helper.assertFalse(
                    core.actor()
                        .isEnhancedLandDestination(point),
                    "Fire was admitted as safe landing");
                helper.setBlock(3, 1, 4, Blocks.air);
                helper.setBlock(3, 0, 4, Blocks.glass);
                removed[0] = target;
                helper.setBlock(target.x - f.x, target.y - f.y - 1, target.z - f.z, Blocks.air);
                changed[0] = f.maid.ticksExisted;
                return false;
            }
            if (f.maid.ticksExisted <= changed[0]) return false;
            var next = core.panic()
                .destination();
            helper.assertTrue(
                !core.actor()
                    .isEnhancedLandDestination(removed[0]),
                "Removed support remained legal");
            helper.assertTrue(f.maid.getHealth() == 20, "Landing validation damaged the actual actor");
            if (next == null) helper.assertTrue(
                f.navigation.noPath() && core.actor()
                    .peekWalkTarget() == null,
                "Invalid destination kept a live route or WALK_TARGET");
            else helper.assertTrue(
                !next.equals(removed[0]) && core.actor()
                    .isEnhancedLandDestination(next),
                "Replacement destination retained invalid support");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 150, batch = "enhanced_panic_air")
    public static void lowAirPreemptsDangerImmediatelyThenBreathingRecoveryAndTaskPermissionApply(
        GameTestHelper helper) {
        var f = fixture(helper, true);
        f.maid.setPosition(f.x + 10.5, f.y + 6, f.z + 4.5);
        f.owner.setPosition(f.x + 10.5, f.y + 6, f.z + 1.5);
        f.maid.setCurrentItemOrArmor(0, new ItemStack(Items.iron_sword));
        f.maid.setAir(300);
        var core = select(helper, f);
        creeper(helper, 14.5, 6);
        int[] phase = { 0 }, changed = { -1 };
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            int age = f.maid.ticksExisted;
            if (phase[0] == 0) {
                if (core.selectedKind() != Kind.PANIC) return false;
                f.maid.setAir(60);
                changed[0] = age;
                phase[0] = 1;
                return false;
            }
            if (age <= changed[0]) return false;
            if (phase[0] == 1) {
                helper.assertTrue(
                    core.selectedKind() == Kind.BREATHE && core.panic()
                        .threat() == null,
                    "Panic hysteresis blocked immediate low-air priority");
                f.maid.addPotionEffect(new PotionEffect(Potion.waterBreathing.id, 200, 0));
                changed[0] = age;
                phase[0] = 2;
                return false;
            }
            if (phase[0] == 2) {
                if (core.selectedKind() != Kind.PANIC) return false;
                helper.assertFalse(
                    core.actor()
                        .isGoingToBreath(),
                    "Breathing effect did not stop air intent");
                f.maid.setTask(MaidTask.ATTACK);
                changed[0] = age;
                phase[0] = 3;
                return false;
            }
            helper.assertTrue(
                core.selectedKind() != Kind.PANIC && core.panic()
                    .threat() == null,
                "Attack task retained idle-only panic");
            return true;
        });
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper, boolean wet) {
        var f = new ModernCoreTests.Fixture(helper, wet, 1, 16);
        f.maid.setSchedule(MaidSchedule.ALL);
        f.owner.setPosition(f.x + 1.5, f.y + (wet ? 6 : 1), f.z + 1.5);
        return f;
    }

    private static EnhancedCoreBrain select(GameTestHelper helper, ModernCoreTests.Fixture f) {
        helper.assertTrue(
            f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED_CORE_COMPONENT)
                == MaidPolicyRuntime.SelectionResult.SELECTED,
            "Enhanced panic selection failed");
        return f.maid.getBrainCoordinator()
            .peekEnhancedCore();
    }

    private static EntityCreeper creeper(GameTestHelper helper, double x, double y) {
        var creeper = new EntityCreeper(helper.getWorld());
        creeper.tasks.taskEntries.clear();
        creeper.targetTasks.taskEntries.clear();
        creeper.func_110163_bv();
        helper.spawnEntity(creeper, x, y, 4.5);
        helper.afterTest(() -> {
            creeper.setDead();
            helper.getWorld()
                .removePlayerEntityDangerously(creeper);
        });
        return creeper;
    }

    private static void ready(GameTestHelper helper, ModernCoreTests.Fixture f, EnhancedCoreBrain core) {
        var brain = f.maid.getBrainCoordinator();
        helper.assertTrue(
            f.maid.isEntityAlive() && brain.peekEnhancedCore() == core
                && brain.peekPolicyRuntime()
                    .isReady()
                && brain.peekFaithfulBrain() == null
                && brain.getFollowController()
                    .getTicks() == 0
                && brain.getCombatController()
                    .getTicks() == 0,
            "Enhanced panic faulted or used another root");
    }

    public static final class CancelHurt {

        final ModernCoreTests.Fixture f;
        boolean cancel;
        int cancelled;

        CancelHurt(ModernCoreTests.Fixture f) {
            this.f = f;
        }

        @SubscribeEvent
        public void hurt(LivingAttackEvent event) {
            if (event.entityLiving == f.maid && cancel) {
                cancelled++;
                event.setCanceled(true);
            }
        }
    }
}
