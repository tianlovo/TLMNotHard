package com.tianluoqaq.tlmnh.qa;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.EnderTeleportEvent;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class EnhancedFollowRecoveryTests {

    private EnhancedFollowRecoveryTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 150, batch = "enhanced_follow_recovery")
    public static void farOwnerRecoversBeyondOldRangeThenSittingAndHomeKeepTheirBoundaries(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false, 10000);
        pad(helper, f);
        f.owner.setPosition(f.x + 72.5, f.y + 1, f.z + 4.5);
        f.maid.setSitting(true);
        var main = new ItemStack(Items.stick, 5);
        var off = new ItemStack(Items.golden_apple, 1, 1);
        off.setStackDisplayName("QA-recovery-offhand");
        f.maid.setCurrentItemOrArmor(0, main.copy());
        f.maid.setOffhandItem(off.copy());
        var core = select(helper, f);
        int[] phase = { 0 }, changed = { 0 };
        long[] attempts = { 0 };
        helper.succeedWhen(() -> {
            check(helper, f, core);
            helper.assertTrue(
                ItemStack.areItemStacksEqual(main, f.maid.getHeldItem())
                    && ItemStack.areItemStacksEqual(off, f.maid.getOffhandItem()),
                "Recovery changed actual hand data");
            int age = f.maid.ticksExisted;
            if (phase[0] == 0) {
                helper.assertTrue(
                    core.follow()
                        .attempts() == 0 && f.maid.getDistanceSqToEntity(f.owner) > 4096,
                    "Sitting command allowed recovery or old far-owner guard masked this fixture");
                if (age < 5) return false;
                f.maid.setSitting(false);
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                if (core.follow()
                    .successes() == 0) return false;
                helper.assertTrue(
                    f.maid.getDistanceSqToEntity(f.owner) < 25 && f.maid.posX > f.x + 65,
                    "Recovery counter did not correspond to actual far-owner relocation");
                var home = f.maid.getHomeArea();
                helper
                    .assertTrue(home.setEnabled(true) && home.capture(Activity.WORK), "Recovery home fixture rejected");
                f.owner.setPosition(f.x + 3.5, f.y + 1, f.z + 4.5);
                attempts[0] = core.follow()
                    .attempts();
                changed[0] = age;
                phase[0] = 2;
                return false;
            }
            helper.assertTrue(
                core.follow()
                    .attempts() == attempts[0] && f.maid.posX > f.x + 65,
                "Home command recovered to an out-of-home owner");
            return age >= changed[0] + 5;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "enhanced_follow_callback")
    public static void unloadedOwnerDoesNotLoadChunksAndTeleportCallbacksCannotCommitOldAuthority(
        GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false, 10000);
        pad(helper, f);
        int farX = f.x + 1_000_000, farZ = f.z + 4;
        helper.assertFalse(
            f.maid.worldObj.getChunkProvider()
                .chunkExists(farX >> 4, farZ >> 4),
            "Unloaded-owner input unexpectedly has an actual chunk");
        f.owner.setPosition(farX + .5, f.y + 1, farZ + .5);
        var core = select(helper, f);
        var hook = new TeleportHook(f);
        MinecraftForge.EVENT_BUS.register(hook);
        helper.afterTest(() -> MinecraftForge.EVENT_BUS.unregister(hook));
        int[] phase = { 0 }, changed = { 0 }, events = { 0 };
        long[] attempts = { 0 };
        helper.succeedWhen(() -> {
            check(helper, f, core);
            int age = f.maid.ticksExisted;
            if (phase[0] == 0) {
                if (core.follow()
                    .attempts() == 0) return false;
                helper.assertTrue(
                    core.follow()
                        .successes() == 0 && hook.events == 0,
                    "Unloaded region produced a recovery destination");
                attempts[0] = core.follow()
                    .attempts();
                changed[0] = age;
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                helper.assertTrue(
                    core.follow()
                        .attempts() == attempts[0]
                        && !f.maid.worldObj.getChunkProvider()
                            .chunkExists(farX >> 4, farZ >> 4),
                    "Recovery retried every tick or generated the owner's chunk");
                if (age < changed[0] + 4) return false;
                f.owner.setPosition(f.x + 72.5, f.y + 1, f.z + 4.5);
                phase[0] = 2;
                return false;
            }
            if (phase[0] == 2) {
                if (hook.events == 0) return false;
                helper.assertTrue(
                    core.follow()
                        .successes() == 0 && f.maid.posX < f.x + 40,
                    "Cancelled Forge event still relocated the actual maid");
                events[0] = hook.events;
                changed[0] = age;
                phase[0] = 3;
                return false;
            }
            if (phase[0] == 3) {
                helper.assertTrue(hook.events == events[0], "Cancelled event caused per-tick callback flooding");
                if (age < changed[0] + 4) return false;
                hook.mode = 1;
                phase[0] = 4;
                return false;
            }
            if (phase[0] == 4) {
                if (hook.events == events[0]) return false;
                helper.assertTrue(
                    core.follow()
                        .successes() == 0 && f.maid.posX < f.x + 40,
                    "Modified event target was forced into a recovery destination");
                events[0] = hook.events;
                hook.mode = 2;
                phase[0] = 5;
                return false;
            }
            if (phase[0] == 5) {
                if (hook.events == events[0]) return false;
                helper.assertTrue(
                    f.maid.isSitting() && core.follow()
                        .successes() == 0 && f.maid.posX < f.x + 40 && f.navigation.noPath(),
                    "Callback pose revocation committed an old relocation or route");
                hook.mode = 3;
                f.maid.setSitting(false);
                phase[0] = 6;
                return false;
            }
            return core.follow()
                .successes() > 0 && f.maid.posX > f.x + 65
                && f.maid.getDistanceSqToEntity(f.owner) < 25;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 90, batch = "faithful_follow_recovery")
    public static void existingFaithfulTeleportStillUsesItsOriginalPlatformBranch(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false, 10000);
        helper.assertTrue(
            f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.FAITHFUL_TLM)
                == MaidPolicyRuntime.SelectionResult.UNCHANGED,
            "Canonical F fixture was not active");
        f.owner.setPosition(f.x + 17.5, f.y + 1, f.z + 4.5);
        helper.succeedWhen(() -> {
            helper.assertTrue(
                f.maid.getHealth() == 20 && f.maid.getBrainCoordinator()
                    .peekPolicyRuntime()
                    .isReady(),
                "Faithful recovery faulted or lost health");
            return f.core.getActor()
                .teleportAttempts() > 0 && f.maid.posX > f.x + 13 && f.maid.getDistanceSqToEntity(f.owner) < 25;
        });
    }

    private static void pad(GameTestHelper helper, ModernCoreTests.Fixture f) {
        for (int x = 69; x <= 76; x++) for (int z = 1; z <= 7; z++) {
            helper.assertTrue(f.maid.worldObj.isAirBlock(f.x + x, f.y, f.z + z), "Far recovery pad overlaps data");
            helper.setBlock(x, 0, z, Blocks.stone);
        }
        helper.afterTest(() -> {
            for (int x = 69; x <= 76; x++)
                for (int z = 1; z <= 7; z++) if (f.maid.worldObj.getBlock(f.x + x, f.y, f.z + z) == Blocks.stone)
                    f.maid.worldObj.setBlockToAir(f.x + x, f.y, f.z + z);
        });
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
                    .peekFaithfulBrain() == null,
            "Recovery used another Brain implementation");
    }

    public static final class TeleportHook {

        final ModernCoreTests.Fixture fixture;
        int mode, events;

        TeleportHook(ModernCoreTests.Fixture fixture) {
            this.fixture = fixture;
        }

        @SubscribeEvent
        public void teleport(EnderTeleportEvent event) {
            if (event.entityLiving != fixture.maid) return;
            events++;
            if (mode == 0) event.setCanceled(true);
            else if (mode == 1) event.targetY = 0;
            else if (mode == 2) fixture.maid.setSitting(true);
        }
    }
}
