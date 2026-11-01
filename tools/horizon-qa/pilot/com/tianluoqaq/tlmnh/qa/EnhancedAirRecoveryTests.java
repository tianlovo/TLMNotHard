package com.tianluoqaq.tlmnh.qa;

import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.EnderTeleportEvent;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.entity.MaidEntityPose;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class EnhancedAirRecoveryTests {

    private EnhancedAirRecoveryTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 130, batch = "enhanced_air_recovery")
    public static void exhaustedAirRescuesToNearbyDryOwnerAfterHomeAndUnderwaterOwnerRelease(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, true, 10000);
        f.owner.setPosition(f.x + 11.5, f.y + 11, f.z + 4.5);
        var main = new ItemStack(Items.diamond_sword, 1, 17);
        var off = new ItemStack(Items.golden_apple, 1, 1);
        off.setStackDisplayName("QA-air-recovery-offhand");
        f.maid.setCurrentItemOrArmor(0, main.copy());
        f.maid.setOffhandItem(off.copy());
        var home = f.maid.getHomeArea();
        helper.assertTrue(home.setEnabled(true) && home.capture(Activity.WORK), "Air recovery home fixture failed");
        var core = select(helper, f);
        f.maid.setAir(99);
        int[] phase = { 0 }, changed = { 0 };
        boolean[] searched = { false };
        helper.succeedWhen(() -> {
            check(helper, f, core);
            helper.assertTrue(
                ItemStack.areItemStacksEqual(main, f.maid.getHeldItem())
                    && ItemStack.areItemStacksEqual(off, f.maid.getOffhandItem()),
                "Air rescue changed actual main/offhand data");
            int age = f.maid.ticksExisted;
            searched[0] |= f.navigation.searchCount() > 0;
            if (phase[0] == 0) {
                helper.assertTrue(
                    core.follow()
                        .attempts() == 0,
                    "Home command allowed air teleport");
                if (age < 6 || !searched[0]) return false;
                helper.assertTrue(home.setEnabled(false), "Home release failed");
                f.owner.setPosition(f.x + 11.5, f.y + 1, f.z + 4.5);
                changed[0] = age;
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                helper.assertTrue(
                    f.owner.isInsideOfMaterial(Material.water) && core.follow()
                        .attempts() == 0,
                    "Submerged owner was accepted as an air rescue target");
                if (age < changed[0] + 6) return false;
                f.owner.setPosition(f.x + 11.5, f.y + 11, f.z + 4.5);
                phase[0] = 2;
                return false;
            }
            if (core.follow()
                .successes() == 0) return false;
            helper.assertTrue(
                searched[0] && f.maid.posY >= f.y + 11
                    && f.maid.getDistanceSqToEntity(f.owner) < 25
                    && !f.maid.isInsideOfMaterial(Material.water)
                    && f.navigation.noPath(),
                "Exhausted air did not produce a real dry relocation below ordinary follow recovery distance");
            return f.maid.getAir() >= 290 && !f.maid.getAquaticController()
                .getManager()
                .isGoingToBreath();
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 140, batch = "enhanced_air_recovery_callback")
    public static void dryLandingIsRequiredBeforeAndAfterForgeCallbackThenAValidRetryRecovers(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, true, 10000);
        // 只有主人的身体列留干，其余全部候选头部在水下；玻璃屋顶承重，不依赖地面寻路
        for (int x = 8; x <= 15; x++)
            for (int y = 11; y <= 14; y++) for (int z = 1; z <= 7; z++) helper.setBlock(x, y, z, Blocks.water);
        for (int y = 11; y <= 14; y++) {
            helper.setBlock(10, y, 4, Blocks.glass);
            helper.setBlock(12, y, 4, Blocks.glass);
            helper.setBlock(11, y, 3, Blocks.glass);
            helper.setBlock(11, y, 5, Blocks.glass);
            helper.setBlock(11, y, 4, Blocks.air);
        }
        helper.afterTest(() -> {
            for (int x = 8; x <= 15; x++)
                for (int y = 11; y <= 14; y++) for (int z = 1; z <= 7; z++) helper.setBlock(x, y, z, Blocks.air);
        });
        f.owner.setPosition(f.x + 11.5, f.y + 11, f.z + 4.5);
        var core = select(helper, f);
        var hook = new LandingHook(f);
        MinecraftForge.EVENT_BUS.register(hook);
        helper.afterTest(() -> MinecraftForge.EVENT_BUS.unregister(hook));
        f.maid.setAir(99);
        int[] phase = { 0 }, changed = { 0 };
        helper.succeedWhen(() -> {
            check(helper, f, core);
            int age = f.maid.ticksExisted;
            if (phase[0] == 0) {
                if (core.follow()
                    .attempts() == 0) return false;
                helper.assertTrue(
                    core.follow()
                        .successes() == 0 && hook.events == 0
                        && f.maid.posY < f.y + 10,
                    "Underwater landing passed the pre-event air check");
                for (int x = 8; x <= 15; x++)
                    for (int y = 11; y <= 14; y++) for (int z = 1; z <= 7; z++) helper.setBlock(x, y, z, Blocks.air);
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                if (hook.events == 0) return false;
                helper.assertTrue(
                    core.follow()
                        .successes() == 0 && f.maid.posY < f.y + 10,
                    "Callback-flooded head space still committed air recovery");
                helper.setBlock(hook.x - f.x, hook.y - f.y, hook.z - f.z, Blocks.air);
                hook.flood = false;
                changed[0] = age;
                phase[0] = 2;
                return false;
            }
            if (age < changed[0] + 4)
                helper.assertTrue(hook.events == 1, "Air callback failure bypassed retry cooldown");
            if (core.follow()
                .successes() == 0) return false;
            helper.assertTrue(
                hook.events > 1 && f.maid.posY >= f.y + 11 && !f.maid.isInsideOfMaterial(Material.water),
                "Valid dry retry did not physically recover");
            return f.maid.getAir() >= 290;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "enhanced_air_landing_pose")
    public static void nativeAirLandingRejectsWaterAboveSwimmingEyesAndAcceptsTheSameClearedSpace(
        GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, true, 10000);
        var core = select(helper, f);
        helper.afterTest(() -> {
            for (int x = 8; x <= 15; x++)
                for (int y = 12; y <= 14; y++) for (int z = 1; z <= 7; z++) helper.setBlock(x, y, z, Blocks.air);
        });
        helper.succeedWhen(() -> {
            check(helper, f, core);
            if (f.maid.ticksExisted < 3 || !core.actor()
                .isCurrent()) return false;
            // 受控原生动作边界：本项直接调用平台入口，不把它记为Root自然救援
            f.owner.setPosition(f.x + 11.5, f.y + 11, f.z + 4.5);
            f.maid.setEntityPose(MaidEntityPose.SWIMMING);
            helper
                .assertTrue(f.maid.height == .6F && f.maid.getEyeHeight() < 1, "Swimming dimensions were not applied");
            for (int x = 8; x <= 15; x++)
                for (int y = 12; y <= 14; y++) for (int z = 1; z <= 7; z++) helper.setBlock(x, y, z, Blocks.water);
            for (int y = 12; y <= 14; y++) {
                helper.setBlock(10, y, 4, Blocks.glass);
                helper.setBlock(12, y, 4, Blocks.glass);
                helper.setBlock(11, y, 3, Blocks.glass);
                helper.setBlock(11, y, 5, Blocks.glass);
                helper.setBlock(11, y, 4, Blocks.air);
            }
            var actor = core.actor();
            var owner = actor.getOwner();
            double beforeX = f.maid.posX, beforeY = f.maid.posY, beforeZ = f.maid.posZ;
            f.maid.getRNG()
                .setSeed(719);
            helper.assertFalse(
                actor.teleportEnhancedToOwner(owner, actor::isCurrent, true),
                "Swimming eyes accepted a landing whose standing head would be underwater");
            helper.assertTrue(
                actor.teleportAttempts() == 10 && f.maid.posX == beforeX
                    && f.maid.posY == beforeY
                    && f.maid.posZ == beforeZ,
                "Unsafe landing changed actual position or skipped finite candidate evaluation");
            for (int x = 8; x <= 15; x++)
                for (int y = 12; y <= 14; y++) for (int z = 1; z <= 7; z++) helper.setBlock(x, y, z, Blocks.air);
            f.maid.getRNG()
                .setSeed(719);
            helper.assertTrue(
                actor.teleportEnhancedToOwner(owner, actor::isCurrent, true) && f.maid.posY == f.y + 11,
                "Cleared standing space did not permit the same real native candidate sequence");
            return true;
        });
    }

    private static EnhancedCoreBrain select(GameTestHelper helper, ModernCoreTests.Fixture f) {
        helper.assertTrue(
            f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED_CORE_COMPONENT)
                == MaidPolicyRuntime.SelectionResult.SELECTED,
            "Independent enhanced air recovery selection failed");
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
            "Air rescue used another Brain implementation");
    }

    public static final class LandingHook {

        final ModernCoreTests.Fixture fixture;
        boolean flood = true;
        int events, x, y, z;

        LandingHook(ModernCoreTests.Fixture fixture) {
            this.fixture = fixture;
        }

        @SubscribeEvent
        public void teleport(EnderTeleportEvent event) {
            if (event.entityLiving != fixture.maid) return;
            events++;
            if (!flood) return;
            x = MathHelper.floor_double(event.targetX);
            y = MathHelper.floor_double(event.targetY + fixture.maid.getEyeHeight());
            z = MathHelper.floor_double(event.targetZ);
            fixture.maid.worldObj.setBlock(x, y, z, Blocks.water, 0, 2);
        }
    }
}
