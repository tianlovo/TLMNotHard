package com.tianluoqaq.tlmnh.qa;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNavigator;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ModernAirTests {

    private ModernAirTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 180, batch = "modern_air_escape")
    public static void lowAirUsesRealBfsAndModernRouteThenClearsRecoveryMemory(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, true);
        f.owner.setPosition(f.x + 5.5, f.y + 2, f.z + 4.5);
        for (int x = 1; x <= 5; x++)
            for (int y = 4; y < 10; y++) for (int z = 1; z < 8; z++) helper.setBlock(x, y, z, Blocks.air);
        f.maid.setAir(95);
        boolean[] going = { false }, path = { false };
        boolean[] missing = { false };
        long[] previousSearches = { -1 };
        int[] recovered = { -1 };
        helper.onEachTick("natural modern low-air escape", () -> {
            f.check();
            var swim = f.maid.getAquaticController()
                .getManager();
            if (missing[0] && swim.isGoingToBreath() && f.maid.getAir() < 100) helper.assertTrue(
                f.navigation.searchCount() > previousSearches[0],
                "Lost breath WALK_TARGET did not cause the next frame's original re-query");
            var walk = f.core.getActor()
                .peekWalkTarget();
            if (f.maid.ticksExisted <= 20 || f.maid.ticksExisted % 20 == 0) {
                var target = walk == null ? null : walk.currentBlockPosition();
                System.out.println(
                    "TLMNH_AIR100_TRACE tick=" + f.maid.ticksExisted
                        + " feet="
                        + f.maid.posY
                        + " air="
                        + f.maid.getAir()
                        + " going="
                        + swim.isGoingToBreath()
                        + " walk="
                        + (target == null ? "null" : target.x + "," + target.y + "," + target.z)
                        + " waterNav="
                        + f.navigation.usesWaterNavigation()
                        + " searches="
                        + f.navigation.searchCount());
            }
            if (swim.isGoingToBreath()) {
                going[0] = true;
                helper.assertFalse(
                    f.core.getActor()
                        .ownerIsWalkTarget(
                            f.core.getActor()
                                .getOwner()),
                    "Owner follow replaced the breathing position");
                path[0] |= !f.navigation.noPath();
            }
            missing[0] = swim.isGoingToBreath() && walk == null;
            previousSearches[0] = f.navigation.searchCount();
            if (going[0] && path[0] && f.maid.getAir() >= 290 && !swim.isGoingToBreath()) {
                helper.assertTrue(
                    f.core.getActor()
                        .peekWalkTarget() == null && f.navigation.noPath(),
                    "Recovered air retained breathing movement");
                if (recovered[0] < 0) recovered[0] = f.maid.ticksExisted;
            }
        });
        helper.succeedWhen(() -> recovered[0] >= 0 && f.maid.ticksExisted >= recovered[0] + 5);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 70, batch = "modern_air_potion")
    public static void breathingPotionUsesExistingInventoryLeaseAndReturnsContainer(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, true);
        preparePotion(f);
        int[] complete = { -1 };
        boolean[] used = { false };
        helper.onEachTick("real temporary hand swap and completion", () -> {
            f.check();
            used[0] |= f.maid.getMealController()
                .isBreathing();
            helper.assertTrue(f.navigation.searchCount() == 0, "Item-first breath task searched for air");
            if (f.maid.isPotionActive(Potion.waterBreathing)) {
                helper.assertTrue(
                    used[0] && count(f, Items.potionitem) == 0 && count(f, Items.glass_bottle) == 1,
                    "Breathing potion or its container was not conserved");
                helper.assertTrue(
                    f.maid.getOffhandItem()
                        .getItem() == Items.stick
                        && f.maid.getHeldItem()
                            .getItem() == Items.diamond_sword,
                    "Temporary breathing hand swap lost displaced equipment");
                helper.assertTrue(
                    f.maid.getItemUseState()
                        .peek() == null,
                    "Completed potion retained use lease");
                if (complete[0] < 0) complete[0] = f.maid.ticksExisted;
            }
        });
        helper.succeedWhen(
            () -> complete[0] >= 0 && f.maid.ticksExisted >= complete[0] + 5
                && !f.maid.getAquaticController()
                    .getManager()
                    .isEatBreatheItem());
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 25, batch = "modern_air_cancel")
    public static void cancelledBreathItemIsRestoredAndRetiredActorKeepsForeignUse(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, true);
        preparePotion(f);
        boolean[] checked = { false };
        helper.onEachTick("live breathing use cancellation", () -> {
            f.check();
            if (checked[0] || f.maid.ticksExisted < 8) return;
            helper.assertTrue(
                f.maid.getMealController()
                    .isBreathing(),
                "No live potion use at cancellation");
            f.core.cancel();
            helper.assertTrue(
                f.maid.getItemUseState()
                    .peek() == null && count(f, Items.potionitem) == 1
                    && count(f, Items.glass_bottle) == 0
                    && f.maid.getOffhandItem()
                        .getItem() == Items.stick,
                "Cancelled breathing use consumed or lost an item");
            var foreign = f.maid.getItemUseState()
                .tryAcquire(
                    new Object(),
                    f.maid.getHeldItem(),
                    MaidItemUseState.Hand.MAIN,
                    MaidItemUseState.Kind.FOOD,
                    () -> true);
            helper.assertTrue(foreign != null, "Foreign successor lease was not installed");
            Object successor = new Object();
            helper
                .assertTrue(f.navigation.claimBrainTarget(successor, new Object(), new MaidPathNavigator.BrainTarget() {

                    public boolean isCurrent() {
                        return true;
                    }

                    public Point currentPosition() {
                        return null;
                    }
                }), "Foreign navigation target owner was not installed");
            var swim = f.maid.getAquaticController()
                .getManager();
            swim.setGoingToBreath(true);
            swim.setEatBreatheItem(true);
            long generation = f.navigation.generation();
            f.core.cancel();
            helper.assertTrue(
                f.maid.getItemUseState()
                    .peek() == foreign && swim.isGoingToBreath()
                    && swim.isEatBreatheItem()
                    && generation == f.navigation.generation()
                    && f.navigation.isBrainTargetOwner(successor),
                "Retired actor changed a successor's item use, breathing flags or navigation state");
            f.navigation.releaseBrainTarget(successor);
            checked[0] = true;
            System.out.println("TLMNH_AIR100_CANCEL_PASS tick=" + f.maid.ticksExisted);
        });
        helper.succeedWhen(() -> checked[0]);
    }

    private static void preparePotion(ModernCoreTests.Fixture f) {
        f.owner.setPosition(f.x + 5.5, f.y + 6, f.z + 4.5);
        var potion = new ItemStack(Items.potionitem);
        var effects = new NBTTagList();
        effects.appendTag(
            new PotionEffect(Potion.waterBreathing.id, 600, 0).writeCustomPotionEffectToNBT(new NBTTagCompound()));
        potion.setTagInfo("CustomPotionEffects", effects);
        var inventory = f.maid.getMaidInventory();
        inventory.setInventorySlotContents(MaidInventory.MAIN_HAND, new ItemStack(Items.diamond_sword));
        inventory.setInventorySlotContents(MaidInventory.OFF_HAND, new ItemStack(Items.stick));
        inventory.setInventorySlotContents(0, potion);
        f.maid.setAir(95);
    }

    private static int count(ModernCoreTests.Fixture f, Item item) {
        int count = 0;
        var inventory = f.maid.getMaidInventory();
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            var stack = inventory.getStackInSlot(slot);
            if (stack != null && stack.getItem() == item) count += stack.stackSize;
        }
        return count;
    }
}
