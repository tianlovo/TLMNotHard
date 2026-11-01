package com.tianluoqaq.tlmnh.qa;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.EnderTeleportEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;
import com.tianluoqaq.tlmnh.compat.etfuturum.common.block.EtFuturumBlocks;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class EnhancedHomeRecoveryTests {

    private EnhancedHomeRecoveryTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 150, batch = "enhanced_home_recovery")
    public static void offlineFarHomeHonorsSittingThenScheduleChangesTheReturnDestination(GameTestHelper helper) {
        var f = fixture(helper);
        pad(helper, f);
        var home = f.maid.getHomeArea();
        f.maid.setPosition(f.x + 72.5, f.y + 1, f.z + 4.5);
        helper.assertTrue(home.setEnabled(true) && home.capture(Activity.WORK), "Far home setup failed");
        f.maid.setPosition(f.x + 3.5, f.y + 1, f.z + 4.5);
        helper.assertTrue(home.capture(Activity.IDLE), "Idle anchor setup failed");
        f.maid.setPosition(f.x + 1.5, f.y + 1, f.z + 4.5);
        helper.getWorld().playerEntities.remove(f.owner);
        MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList.remove(f.owner);
        var main = new ItemStack(Items.stick, 5);
        var off = new ItemStack(Items.golden_apple, 1, 1);
        off.setStackDisplayName("QA-home-recovery-offhand");
        f.maid.setCurrentItemOrArmor(0, main.copy());
        f.maid.setOffhandItem(off.copy());
        f.maid.setSitting(true);
        var core = select(helper, f);
        int[] phase = { 0 };
        helper.succeedWhen(() -> {
            check(helper, f, core);
            helper.assertTrue(
                ItemStack.areItemStacksEqual(main, f.maid.getHeldItem())
                    && ItemStack.areItemStacksEqual(off, f.maid.getOffhandItem()),
                "Home recovery changed actual hand data");
            int age = f.maid.ticksExisted;
            if (phase[0] == 0) {
                helper.assertTrue(
                    core.homeReturn()
                        .attempts() == 0 && f.maid.posX < f.x + 10,
                    "Sitting command allowed a home recovery");
                if (age < 5) return false;
                f.maid.setSitting(false);
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                if (core.homeReturn()
                    .successes() == 0) return false;
                helper.assertTrue(
                    f.maid.getOwner() == null && f.maid.posX > f.x + 65 && home.containsMaid(),
                    "Offline home counter did not represent a real far-home relocation");
                helper.getWorld()
                    .setWorldTime(12000);
                phase[0] = 2;
                return false;
            }
            if (home.activity() != Activity.IDLE || home.restrictionCenter().x != f.x + 3) return false;
            if (core.homeReturn()
                .successes() < 2) return false;
            helper.assertTrue(f.maid.posX < f.x + 10 && home.containsMaid(), "Schedule returned to the retired anchor");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 280, batch = "enhanced_home_callbacks")
    public static void unloadedHomeAndForgeRevocationKeepDataUntilLoadedGroundAllowsReturn(GameTestHelper helper) {
        var f = fixture(helper);
        pad(helper, f);
        int farX = f.x + 1_000_000, farZ = f.z + 4;
        helper.assertFalse(
            helper.getWorld()
                .getChunkProvider()
                .chunkExists(farX >> 4, farZ >> 4),
            "Unloaded home fixture already has an actual chunk");
        var home = f.maid.getHomeArea();
        f.maid.setPosition(farX + .5, f.y + 1, farZ + .5);
        helper.assertTrue(home.setEnabled(true) && home.capture(Activity.WORK), "Unloaded home setup failed");
        f.maid.setPosition(f.x + 1.5, f.y + 1, f.z + 4.5);
        var core = select(helper, f);
        var hook = new HomeHook(f);
        MinecraftForge.EVENT_BUS.register(hook);
        helper.afterTest(() -> MinecraftForge.EVENT_BUS.unregister(hook));
        int[] phase = { 0 }, changed = { 0 }, events = { 0 };
        long[] attempts = { 0 };
        helper.succeedWhen(() -> {
            check(helper, f, core);
            int age = f.maid.ticksExisted;
            if (phase[0] == 0) {
                if (core.homeReturn()
                    .attempts() == 0) return false;
                helper.assertTrue(
                    core.homeReturn()
                        .successes() == 0 && hook.events == 0
                        && !helper.getWorld()
                            .getChunkProvider()
                            .chunkExists(farX >> 4, farZ >> 4),
                    "Home recovery generated an unloaded chunk or a Forge destination");
                attempts[0] = core.homeReturn()
                    .attempts();
                changed[0] = age;
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                helper.assertTrue(
                    core.homeReturn()
                        .attempts() == attempts[0],
                    "Unloaded home bypassed retry cooldown");
                if (age < changed[0] + 4) return false;
                f.maid.setPosition(f.x + 72.5, f.y + 1, f.z + 4.5);
                helper.assertTrue(home.capture(Activity.WORK), "Loaded anchor change failed");
                f.maid.setPosition(f.x + 1.5, f.y + 1, f.z + 4.5);
                phase[0] = 2;
                return false;
            }
            if (phase[0] == 2) {
                if (hook.events == 0) return false;
                helper.assertTrue(
                    core.homeReturn()
                        .successes() == 0 && f.maid.posX < f.x + 40,
                    "Cancelled Forge home event committed relocation");
                events[0] = hook.events;
                changed[0] = age;
                phase[0] = 3;
                return false;
            }
            if (phase[0] == 3) {
                helper.assertTrue(hook.events == events[0], "Cancelled home event retried every tick");
                if (age < changed[0] + 4) return false;
                hook.mode = 1;
                phase[0] = 4;
                return false;
            }
            if (phase[0] == 4) {
                if (hook.events == events[0]) return false;
                helper.assertTrue(
                    !home.enabled() && core.homeReturn()
                        .successes() == 0 && f.maid.posX < f.x + 40,
                    "Home command revocation still committed the old return");
                helper.assertTrue(home.setEnabled(true), "Home re-enable failed");
                hook.mode = 2;
                events[0] = hook.events;
                phase[0] = 5;
                return false;
            }
            if (phase[0] == 5) {
                if (hook.events == events[0]) return false;
                helper.assertTrue(
                    core.homeReturn()
                        .successes() == 0 && f.maid.posX < f.x + 40,
                    "Removed support still committed a stale home landing");
                helper.getWorld()
                    .setBlock(hook.x, hook.y, hook.z, Blocks.stone, 0, 2);
                hook.mode = 3;
                phase[0] = 6;
                return false;
            }
            return core.homeReturn()
                .successes() > 0 && f.maid.posX > f.x + 65
                && home.containsMaid();
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 130, batch = "enhanced_home_hazard")
    public static void actualMagmaSupportIsRejectedThenStoneAllowsTheNaturalHomeReturn(GameTestHelper helper) {
        var f = fixture(helper);
        pad(helper, f);
        Block magma = null;
        for (Object value : Block.blockRegistry) if (value instanceof Block block && EtFuturumBlocks.isMagma(block)) {
            magma = block;
            break;
        }
        helper.assertTrue(magma != null, "Installed Et Futurum magma block is missing");
        for (int x = 69; x <= 76; x++) for (int z = 1; z <= 7; z++) helper.setBlock(x, 0, z, magma);
        var home = f.maid.getHomeArea();
        f.maid.setPosition(f.x + 72.5, f.y + 1, f.z + 4.5);
        helper.assertTrue(home.setEnabled(true) && home.capture(Activity.WORK), "Magma home setup failed");
        f.maid.setPosition(f.x + 1.5, f.y + 1, f.z + 4.5);
        var core = select(helper, f);
        int[] phase = { 0 };
        helper.succeedWhen(() -> {
            check(helper, f, core);
            if (phase[0] == 0) {
                if (core.homeReturn()
                    .attempts() == 0) return false;
                helper.assertTrue(
                    core.homeReturn()
                        .successes() == 0
                        && core.actor()
                            .homeTeleportAttempts() == 10
                        && f.maid.posX < f.x + 40,
                    "Actual magma was accepted as safe home support");
                for (int x = 69; x <= 76; x++) for (int z = 1; z <= 7; z++) helper.setBlock(x, 0, z, Blocks.stone);
                phase[0] = 1;
                return false;
            }
            return core.homeReturn()
                .successes() > 0 && f.maid.posX > f.x + 65
                && home.containsMaid();
        });
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false, 10000);
        f.maid.setSchedule(MaidSchedule.DAY);
        return f;
    }

    private static void pad(GameTestHelper helper, ModernCoreTests.Fixture f) {
        for (int x = 69; x <= 76; x++) for (int z = 1; z <= 7; z++) {
            helper.assertTrue(
                helper.getWorld()
                    .isAirBlock(f.x + x, f.y, f.z + z),
                "Far home pad overlaps data");
            helper.getWorld()
                .setBlock(f.x + x, f.y, f.z + z, Blocks.stone, 0, 2);
        }
        helper.afterTest(() -> {
            for (int x = 69; x <= 76; x++) for (int z = 1; z <= 7; z++) if (helper.getWorld()
                .getBlock(f.x + x, f.y, f.z + z) == Blocks.stone)
                helper.getWorld()
                    .setBlockToAir(f.x + x, f.y, f.z + z);
        });
    }

    private static EnhancedCoreBrain select(GameTestHelper helper, ModernCoreTests.Fixture f) {
        helper.assertTrue(
            f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED_CORE_COMPONENT)
                == MaidPolicyRuntime.SelectionResult.SELECTED,
            "Independent enhanced home selection failed");
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
            "Home return used another Brain");
    }

    public static final class HomeHook {

        final ModernCoreTests.Fixture f;
        int mode, events, x, y, z;

        HomeHook(ModernCoreTests.Fixture f) {
            this.f = f;
        }

        @SubscribeEvent
        public void teleport(EnderTeleportEvent event) {
            if (event.entityLiving != f.maid) return;
            events++;
            if (mode == 0) event.setCanceled(true);
            else if (mode == 1) f.maid.getHomeArea()
                .setEnabled(false);
            else if (mode == 2) {
                x = MathHelper.floor_double(event.targetX);
                y = MathHelper.floor_double(event.targetY) - 1;
                z = MathHelper.floor_double(event.targetZ);
                f.maid.worldObj.setBlockToAir(x, y, z);
            }
        }
    }
}
