package com.tianluoqaq.tlmnh.qa;

import net.minecraft.entity.item.EntityMinecartEmpty;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.MaidWorldNavigationBudget;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidClimbActor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;
import com.tianluoqaq.tlmnh.common.config.MaidNavigationConfig;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class EnhancedRecoveryTests {

    private EnhancedRecoveryTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 180, batch = "enhanced_recovery_air")
    public static void submergedPassengerLeavesForAirKeepsForeignUseAndActuallyRecovers(GameTestHelper helper) {
        int oldLimit = MaidNavigationConfig.queriesPerTick;
        MaidNavigationConfig.queriesPerTick = 1;
        MaidWorldNavigationBudget.unload(helper.getWorld());
        helper.afterTest(() -> {
            MaidNavigationConfig.queriesPerTick = oldLimit;
            MaidWorldNavigationBudget.unload(helper.getWorld());
        });
        var f = fixture(helper, true);
        for (int x = 5; x <= 7; x++)
            for (int y = 4; y < 10; y++) for (int z = 1; z < 8; z++) helper.setBlock(x, y, z, Blocks.air);
        f.owner.setPosition(f.x + 5.5, f.y + 2, f.z + 4.5);
        var shield = new ItemStack((Item) Item.itemRegistry.getObject("battlegear2:shield.wood"));
        f.maid.setOffhandItem(shield);
        var cart = cart(helper, f);
        var core = select(helper, f);
        MaidItemUseState.Lease[] foreign = { null };
        int[] phase = { 0 };
        boolean[] path = { false };
        long[] previousSearches = { 0 };
        var trace = new StringBuilder();
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            if (f.maid.ticksExisted % 5 == 0 && trace.length() < 12000) trace.append(" age=")
                .append(f.maid.ticksExisted)
                .append(" pos=")
                .append(f.maid.posX - f.x)
                .append(',')
                .append(f.maid.posY - f.y)
                .append(',')
                .append(f.maid.posZ - f.z)
                .append(" air=")
                .append(f.maid.getAir())
                .append(" hp=")
                .append(f.maid.getHealth())
                .append(" use=")
                .append(
                    f.maid.getItemUseState()
                        .peek() == foreign[0])
                .append(" nav=")
                .append(
                    core.navigation()
                        .outcome())
                .append(" searches=")
                .append(f.navigation.searchCount())
                .append(" noPath=")
                .append(f.navigation.noPath())
                .append(" going=")
                .append(
                    f.maid.getAquaticController()
                        .getManager()
                        .isGoingToBreath())
                .append(" target=")
                .append(
                    core.actor()
                        .isCurrent()
                            ? core.actor()
                                .walkTargetPosition()
                            : "inactive");
            long searched = f.navigation.searchCount();
            helper.assertTrue(searched - previousSearches[0] <= 1, "Emergency air exceeded one actual A* per tick");
            previousSearches[0] = searched;
            if (phase[0] == 0) {
                if (f.maid.ticksExisted < 4) return false;
                foreign[0] = f.maid.getItemUseState()
                    .tryAcquire(
                        new Object(),
                        shield,
                        MaidItemUseState.Hand.OFF,
                        MaidItemUseState.Kind.SHIELD,
                        () -> f.maid.getOffhandItem() == shield);
                helper.assertTrue(
                    foreign[0] != null && f.maid.ridingEntity == cart,
                    "Warm registered passenger/foreign use fixture failed");
                f.maid.setAir(60);
                phase[0] = 1;
                return false;
            }
            helper.assertTrue(
                f.maid.getItemUseState()
                    .peek() == foreign[0] && f.maid.getOffhandItem() == shield
                    && shield.getItemDamage() == 0
                    && f.maid.getHealth() == 20,
                "Emergency movement stole foreign use, equipment or health" + trace);
            path[0] |= !f.navigation.noPath();
            if (f.maid.getAir() < 290 || f.maid.getAquaticController()
                .getManager()
                .isGoingToBreath()) return false;
            helper.assertTrue(
                path[0] && f.maid.ridingEntity == null
                    && core.breathing()
                        .emergencyReleases() == 1
                    && f.maid.posX > f.x + 4
                    && f.maid.posY > f.y + 2.5,
                "Passenger did not physically leave, swim to air and recover once");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "enhanced_recovery_potion")
    public static void breathingItemCompletesInSeatAndRestoresBothHandsWithoutEmergencyRelease(GameTestHelper helper) {
        var f = fixture(helper, true);
        var sword = new ItemStack(Items.diamond_sword);
        var off = new ItemStack(Items.stick);
        f.maid.setCurrentItemOrArmor(0, sword);
        f.maid.setOffhandItem(off);
        var cart = cart(helper, f);
        var core = select(helper, f);
        int[] armed = { -1 };
        boolean[] used = { false };
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            helper.assertTrue(
                f.maid.ridingEntity == cart && core.breathing()
                    .emergencyReleases() == 0 && f.navigation.searchCount() == 0,
                "Available breath item still caused dismount or path search");
            if (armed[0] < 0) {
                if (f.maid.ticksExisted < 4) return false;
                var potion = new ItemStack(Items.potionitem);
                var effects = new NBTTagList();
                effects.appendTag(
                    new PotionEffect(Potion.waterBreathing.id, 600, 0)
                        .writeCustomPotionEffectToNBT(new NBTTagCompound()));
                potion.setTagInfo("CustomPotionEffects", effects);
                f.maid.getMaidInventory()
                    .setInventorySlotContents(0, potion);
                f.maid.setAir(60);
                armed[0] = f.maid.ticksExisted;
                return false;
            }
            used[0] |= f.maid.getMealController()
                .isBreathing();
            if (!f.maid.isPotionActive(Potion.waterBreathing)) return false;
            helper.assertTrue(
                used[0] && count(f, Items.potionitem) == 0
                    && count(f, Items.glass_bottle) == 1
                    && f.maid.getHeldItem() == sword
                    && f.maid.getOffhandItem() == off
                    && f.maid.getItemUseState()
                        .peek() == null,
                "Seat breath item did not settle one finite use/container");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "enhanced_recovery_partial")
    public static void exhaustedPartialWaitsWithoutLosingGoalThenOpenedBarrierResumesActualMovement(
        GameTestHelper helper) {
        int old = MaidNavigationConfig.queriesPerTick;
        MaidNavigationConfig.queriesPerTick = 1;
        MaidWorldNavigationBudget.unload(helper.getWorld());
        helper.afterTest(() -> {
            MaidNavigationConfig.queriesPerTick = old;
            MaidWorldNavigationBudget.unload(helper.getWorld());
        });
        var f = new ModernCoreTests.Fixture(helper, false, 1, 24);
        f.maid.setSchedule(MaidSchedule.ALL);
        f.owner.setPosition(f.x + 18.5, f.y + 1, f.z + 4.5);
        for (int x = 1; x < 20; x++) {
            helper.setBlock(x, 0, 4, Blocks.stone);
            for (int y = 1; y <= 2; y++) {
                helper.setBlock(x, y, 3, Blocks.glass);
                helper.setBlock(x, y, 5, Blocks.glass);
            }
            helper.setBlock(x, 3, 4, Blocks.glass);
        }
        helper.setBlock(8, 1, 4, Blocks.glass);
        helper.setBlock(8, 2, 4, Blocks.glass);
        var core = select(helper, f);
        int[] phase = { 0 }, began = { -1 };
        long[] waitedSearches = { -1 }, previous = { 0 };
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            int age = f.maid.ticksExisted;
            long searched = f.navigation.searchCount();
            helper.assertTrue(searched - previous[0] <= 1, "Recovery exceeded actual world A* limit");
            previous[0] = searched;
            if (phase[0] == 0) {
                if (core.navigation()
                    .recoveryCount() == 0) return false;
                helper.assertTrue(
                    core.navigation()
                        .partialPathCount() > 0 && f.navigation.noPath()
                        && core.actor()
                            .peekWalkTarget() != null
                        && core.actor()
                            .peekCantReachSince() != null,
                    "Exhausted partial route did not retain goal/failure memory while stopping only its route");
                began[0] = age;
                waitedSearches[0] = searched;
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                helper.assertTrue(
                    searched == waitedSearches[0] && core.actor()
                        .peekWalkTarget() != null,
                    "Recovery delay re-queried every frame or erased owner goal");
                if (age < began[0] + 10) return false;
                helper.setBlock(8, 1, 4, Blocks.air);
                helper.setBlock(8, 2, 4, Blocks.air);
                phase[0] = 2;
                return false;
            }
            if (f.maid.posX < f.x + 10) return false;
            helper.assertTrue(
                searched > waitedSearches[0] && core.actor()
                    .peekCantReachSince() == null,
                "Opened real barrier did not produce a fresh reachable route and physical progress");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 400, batch = "enhanced_recovery_climb")
    public static void actualLadderClimbsCancelsOldCapabilityResumesAndReturnsDown(GameTestHelper helper) {
        var f = ModernClimbTests.fixture(helper, 6, 8);
        var core = select(helper, f);
        int[] phase = { 0 }, stopped = { -1 };
        boolean[] down = { false }, vertical = { false };
        MaidClimbActor.ClimbPath[] old = { null };
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            int age = f.maid.ticksExisted;
            helper.assertTrue(
                core.actor()
                    .teleportAttempts() == 0
                    && core.actor()
                        .homeTeleportAttempts() == 0,
                "Climb round trip used owner/home teleport");
            if (phase[0] == 0) {
                if (!f.maid.isCanClimb() || f.navigation.noPath()) return false;
                old[0] = core.actor()
                    .climbPath();
                f.maid.getBrainCoordinator()
                    .setSuspended(true);
                helper.assertTrue(
                    !f.maid.isCanClimb() && !f.maid.isSneaking() && f.navigation.noPath(),
                    "Suspension retained climb flags or route");
                stopped[0] = age;
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                if (age < stopped[0] + 3) return false;
                f.maid.getBrainCoordinator()
                    .setSuspended(false);
                phase[0] = 2;
                return false;
            }
            if (phase[0] == 2) {
                boolean expired = false;
                try {
                    old[0].index(0);
                } catch (IllegalStateException expected) {
                    expired = true;
                }
                helper.assertTrue(expired, "Old climb capability obtained fresh route authority");
                phase[0] = 3;
            }
            if (phase[0] == 3 && f.maid.posX >= f.x + 6
                && f.maid.posY >= f.y + 6.9
                && f.maid.onGround
                && core.actor()
                    .peekWalkTarget() == null) {
                f.owner.setPosition(f.x + 1.5, f.y + 1, f.z + 4.5);
                phase[0] = 4;
            }
            if (phase[0] != 4) return false;
            down[0] |= f.maid.isCanClimb() && f.maid.isSneaking() && f.maid.motionY < 0;
            var path = f.navigation.getPath();
            if (path != null) for (int i = 1; i < path.getCurrentPathLength(); i++) {
                var a = path.getPathPointFromIndex(i - 1);
                var b = path.getPathPointFromIndex(i);
                vertical[0] |= a.xCoord == b.xCoord && a.zCoord == b.zCoord && b.yCoord < a.yCoord;
            }
            return down[0] && vertical[0]
                && f.maid.posX < f.x + 4
                && f.maid.posY < f.y + 1.2
                && !f.maid.isCanClimb()
                && !f.maid.isSneaking();
        });
    }

    private static EntityMinecartEmpty cart(GameTestHelper helper, ModernCoreTests.Fixture f) {
        var cart = new EntityMinecartEmpty(helper.getWorld());
        helper.spawnEntity(cart, 1.5, 1.4, 4.5);
        helper.afterTest(() -> {
            f.maid.mountEntity(null);
            cart.setDead();
        });
        f.maid.mountEntity(cart);
        return cart;
    }

    private static int count(ModernCoreTests.Fixture f, Item item) {
        int count = 0;
        for (int i = 0; i < f.maid.getMaidInventory()
            .getSizeInventory(); i++) {
            var s = f.maid.getMaidInventory()
                .getStackInSlot(i);
            if (s != null && s.getItem() == item) count += s.stackSize;
        }
        return count;
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper, boolean wet) {
        var f = new ModernCoreTests.Fixture(helper, wet, 2.25, 16);
        f.maid.setSchedule(MaidSchedule.ALL);
        f.owner.setPosition(f.x + 1.5, f.y + (wet ? 6 : 1), f.z + 1.5);
        return f;
    }

    private static EnhancedCoreBrain select(GameTestHelper helper, ModernCoreTests.Fixture f) {
        helper.assertTrue(
            f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED_CORE_COMPONENT)
                == MaidPolicyRuntime.SelectionResult.SELECTED,
            "Enhanced recovery selection failed");
        return f.maid.getBrainCoordinator()
            .peekEnhancedCore();
    }

    private static void ready(GameTestHelper helper, ModernCoreTests.Fixture f, EnhancedCoreBrain core) {
        var b = f.maid.getBrainCoordinator();
        helper.assertTrue(
            f.maid.isEntityAlive() && b.peekEnhancedCore() == core
                && b.peekPolicyRuntime()
                    .isReady()
                && b.peekFaithfulBrain() == null
                && b.getFollowController()
                    .getTicks() == 0
                && b.getCombatController()
                    .getTicks() == 0,
            "Enhanced recovery faulted or used another root");
    }
}
