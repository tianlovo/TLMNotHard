package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.projectile.EntitySnowball;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidCombatActor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class EnhancedSnowballTests {

    private EnhancedSnowballTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "enhanced_snow_hands")
    public static void actualOwnerlessFlightsKeepBothSnowballHandsAndRejectTwoOccupiedSlots(GameTestHelper helper) {
        var f = ModernLeisureTests.fixture(helper, true);
        registerOwner(helper, f);
        var core = select(helper, f);
        var hook = hook(helper, f, core);
        var feather = new ItemStack(Items.feather, 5);
        feather.setStackDisplayName("QA-preserved-main");
        var apple = new ItemStack(Items.golden_apple, 1, 1);
        apple.setStackDisplayName("QA-preserved-offhand");
        var cake = new ItemStack(Items.cake, 2);
        cake.setStackDisplayName("QA-request-during-play");
        int[] phase = { 0 }, changed = { 0 }, firstBalls = { 0 };
        long[] throwsBefore = { 0 };
        helper.succeedWhen(() -> {
            check(helper, f, core);
            helper.assertTrue(
                hook.ownerless && f.owner.getHealth() == 20 && f.owner.getAITarget() == null,
                "Play projectile gained attacker attribution, hurt or revenge on owner");
            int age = f.maid.ticksExisted;
            if (phase[0] == 0) {
                if (core.actor()
                    .snowballThrows() == 0 || !hook.movedAfter(0)) return false;
                helper.assertTrue(
                    f.maid.getHeldItem() != null && f.maid.getHeldItem()
                        .getItem() == Items.snowball
                        && f.maid.getHeldItem().stackSize == 1
                        && f.maid.getSnowballAnimation()
                            .sequence() > 0,
                    "Native main-hand pickup or unconsumed snowball was absent");
                firstBalls[0] = hook.balls.size();
                throwsBefore[0] = core.actor()
                    .snowballThrows();
                f.owner.inventory.mainInventory[f.owner.inventory.currentItem] = cake.copy();
                changed[0] = age;
                phase[0] = 4;
                return false;
            }
            if (phase[0] == 4) {
                if (age <= changed[0] + 1) return false;
                helper.assertTrue(
                    core.selectedKind() == Kind.BEG && f.maid.hasPose(MaidPose.BEGGING)
                        && core.snowball()
                            .handLease() == null
                        && ItemStack.areItemStacksEqual(cake, f.owner.getHeldItem())
                        && f.maid.getHeldItem()
                            .getItem() == Items.snowball
                        && f.maid.getHeldItem().stackSize == 1,
                    "Live food request could not retire play or consumed either held stack");
                f.owner.inventory.mainInventory[f.owner.inventory.currentItem] = null;
                f.maid.setCurrentItemOrArmor(0, feather.copy());
                f.maid.setOffhandItem(null);
                changed[0] = age;
                phase[0] = 1;
                return false;
            }
            if (age <= changed[0]) return false;
            if (phase[0] == 1) {
                helper.assertTrue(
                    core.selectedKind() != Kind.SNOWBALL && core.snowball()
                        .handLease() == null
                        && f.maid.getModernCombatMemory()
                            .target() == null,
                    "Changed hand retained the old play action, hand capability or friendly target");
                phase[0] = 2;
                return false;
            }
            if (phase[0] == 2) {
                if (core.actor()
                    .snowballThrows() <= throwsBefore[0] || !hook.movedAfter(firstBalls[0])) return false;
                helper.assertTrue(
                    ItemStack.areItemStacksEqual(feather, f.maid.getHeldItem()) && f.maid.getOffhandItem() != null
                        && f.maid.getOffhandItem()
                            .getItem() == Items.snowball
                        && f.maid.getOffhandItem().stackSize == 1
                        && f.maid.hasPose(MaidPose.SWING_OFFHAND),
                    "Offhand play replaced the main stack, consumed the ball or swung the wrong hand");
                f.maid.setOffhandItem(apple.copy());
                throwsBefore[0] = core.actor()
                    .snowballThrows();
                changed[0] = age;
                phase[0] = 3;
                return false;
            }
            helper.assertTrue(
                ItemStack.areItemStacksEqual(feather, f.maid.getHeldItem())
                    && ItemStack.areItemStacksEqual(apple, f.maid.getOffhandItem())
                    && core.actor()
                        .snowballThrows() == throwsBefore[0]
                    && core.snowball()
                        .handLease() == null,
                "Two occupied slots were replaced or a detached snowball hand continued throwing");
            return age >= changed[0] + 8;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "enhanced_snow_callback")
    public static void spawnHandChangeKillsOldProjectileThenCancellationKeepsForeignLeaseAndFreshPlayResumes(
        GameTestHelper helper) {
        var f = ModernLeisureTests.fixture(helper, true);
        registerOwner(helper, f);
        var core = select(helper, f);
        var hook = hook(helper, f, core);
        hook.changeHand = true;
        int[] phase = { 0 }, changed = { 0 }, priorBalls = { 0 };
        var foreign = new MaidItemUseState.Lease[1];
        helper.afterTest(
            () -> f.maid.getItemUseState()
                .release(foreign[0]));
        helper.succeedWhen(() -> {
            check(helper, f, core);
            helper.assertTrue(hook.ownerless, "Enhanced callback path gained a projectile owner");
            int age = f.maid.ticksExisted;
            if (phase[0] == 0) {
                if (!hook.changed || hook.balls.isEmpty()) return false;
                helper.assertTrue(
                    hook.balls.get(0).isDead && core.actor()
                        .snowballThrows() == 0 && ItemStack.areItemStacksEqual(hook.replacement, f.maid.getHeldItem()),
                    "Actual hand-changing spawn callback retained an unauthorized projectile");
                f.maid.getBrainCoordinator()
                    .setSuspended(true);
                helper.assertTrue(
                    core.snowball()
                        .target() == null
                        && core.snowball()
                            .handLease() == null,
                    "Cancellation retained a play target or hand lease");
                boolean rejected = false;
                try {
                    core.actor()
                        .throwSnowballAt(hook.oldTarget);
                } catch (IllegalStateException expected) {
                    rejected = true;
                }
                helper.assertTrue(rejected && hook.balls.size() == 1, "Retired frame created another projectile");
                f.maid.getBrainCoordinator()
                    .setSuspended(false);
                foreign[0] = f.maid.getItemUseState()
                    .tryAcquire(
                        new Object(),
                        new Object(),
                        MaidItemUseState.Hand.OFF,
                        MaidItemUseState.Kind.FOOD,
                        () -> true);
                helper.assertTrue(foreign[0] != null, "Foreign hand capability did not enter the actual maid state");
                core.snowball()
                    .cancel();
                helper.assertTrue(
                    f.maid.getItemUseState()
                        .peek() == foreign[0],
                    "Late play cleanup erased a newer hand capability");
                changed[0] = age;
                phase[0] = 1;
                return false;
            }
            if (age <= changed[0]) return false;
            if (phase[0] == 1) {
                helper.assertTrue(
                    f.maid.getItemUseState()
                        .peek() == foreign[0]
                        && core.actor()
                            .snowballThrows() == 0
                        && core.selectedKind() != Kind.SNOWBALL,
                    "Resumed play erased another owner hand lease or threw through it");
                if (age < changed[0] + 5) return false;
                f.maid.getItemUseState()
                    .release(foreign[0]);
                priorBalls[0] = hook.balls.size();
                phase[0] = 2;
                return false;
            }
            if (core.actor()
                .snowballThrows() == 0 || !hook.movedAfter(priorBalls[0])) return false;
            helper.assertTrue(
                ItemStack.areItemStacksEqual(hook.replacement, f.maid.getHeldItem()) && f.maid.getOffhandItem() != null
                    && f.maid.getOffhandItem()
                        .getItem() == Items.snowball
                    && f.maid.getOffhandItem().stackSize == 1,
                "Fresh offhand play overwrote callback-owned items or consumed its native snowball");
            return true;
        });
    }

    private static void registerOwner(GameTestHelper helper, ModernCoreTests.Fixture f) {
        var world = helper.getWorld();
        world.getChunkFromBlockCoords((int) Math.floor(f.owner.posX), (int) Math.floor(f.owner.posZ))
            .removeEntity(f.owner);
        world.playerEntities.remove(f.owner);
        helper.assertTrue(
            world.spawnEntityInWorld(f.owner) && world.getEntityByID(f.owner.getEntityId()) == f.owner,
            "Snow play fixture owner must be registered in the actual world entity table");
        helper.afterTest(() -> world.removePlayerEntityDangerously(f.owner));
    }

    private static EnhancedCoreBrain select(GameTestHelper helper, ModernCoreTests.Fixture f) {
        helper.assertTrue(
            f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED_CORE_COMPONENT)
                == MaidPolicyRuntime.SelectionResult.SELECTED,
            "Independent enhanced selection failed");
        return f.maid.getBrainCoordinator()
            .peekEnhancedCore();
    }

    private static void check(GameTestHelper helper, ModernCoreTests.Fixture f, EnhancedCoreBrain core) {
        f.check();
        helper.assertTrue(
            f.maid.getBrainCoordinator()
                .peekEnhancedCore() == core
                && f.maid.getBrainCoordinator()
                    .peekFaithfulBrain() == null
                && f.maid.getBrainCoordinator()
                    .getCombatController()
                    .getTicks() == 0,
            "Snow play delegated decisions to F or legacy root");
    }

    private static BallHook hook(GameTestHelper helper, ModernCoreTests.Fixture f, EnhancedCoreBrain core) {
        var hook = new BallHook(f, core);
        MinecraftForge.EVENT_BUS.register(hook);
        helper.afterTest(() -> {
            MinecraftForge.EVENT_BUS.unregister(hook);
            for (var ball : hook.balls) ball.setDead();
        });
        return hook;
    }

    public static final class BallHook {

        final ModernCoreTests.Fixture f;
        final EnhancedCoreBrain core;
        final List<EntitySnowball> balls = new ArrayList<>();
        final List<double[]> starts = new ArrayList<>();
        final ItemStack replacement = new ItemStack(Items.stick, 7);
        boolean changeHand, changed, ownerless = true;
        MaidCombatActor.Target oldTarget;

        BallHook(ModernCoreTests.Fixture f, EnhancedCoreBrain core) {
            this.f = f;
            this.core = core;
            replacement.setStackDisplayName("QA-callback-owned-hand");
        }

        @SubscribeEvent
        public void joining(EntityJoinWorldEvent event) {
            if (!(event.entity instanceof EntitySnowball ball) || event.world != f.maid.worldObj
                || ball.getDistanceSqToEntity(f.maid) > .25) return;
            balls.add(ball);
            starts.add(new double[] { ball.posX, ball.posY, ball.posZ });
            ownerless &= ball.getThrower() == null;
            if (changeHand && !changed) {
                changed = true;
                oldTarget = core.actor()
                    .combatTargetView(f.owner);
                f.maid.setCurrentItemOrArmor(0, replacement.copy());
            }
        }

        boolean movedAfter(int start) {
            for (int i = start; i < balls.size(); i++) {
                var ball = balls.get(i);
                var origin = starts.get(i);
                if (ball.ticksExisted > 0 && ball.getDistanceSq(origin[0], origin[1], origin[2]) > .01) return true;
            }
            return false;
        }
    }
}
