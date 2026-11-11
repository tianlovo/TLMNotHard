package com.tianluoqaq.tlmnh.qa;

import java.util.UUID;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.backpack.EmptyBackpackContainer;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.inventory.container.ContainerMaid;
import com.tianluoqaq.tlmnh.common.lifecycle.world.MaidSpawnChecks;

/** 原生原版窗口的服务端任务按钮；不将此记录称为真实客户端鼠标或自然战斗 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa", "etfuturum" })
public final class MaidTaskEquipmentTests {

    private MaidTaskEquipmentTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 80, batch = "task_equipment_1_legacy")
    public static void ordinaryTaskButtonsSwapOriginalWeaponStacksUnderLegacy(GameTestHelper helper) {
        exercise(helper, MaidBrainProfile.LEGACY_PROTOTYPE);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 80, batch = "task_equipment_2_enhanced")
    public static void ordinaryTaskButtonsSwapOriginalWeaponStacksUnderEnhanced(GameTestHelper helper) {
        exercise(helper, MaidBrainProfile.ENHANCED);
    }

    private static void exercise(GameTestHelper helper, MaidBrainProfile profile) {
        var f = new RedemptionDurabilityTests.Fixture(helper, UUID.randomUUID(), false);
        var maid = new EntityMaid(helper.getWorld());
        helper.assertTrue(maid.bindTo(f.player.getUniqueID()), "Weapon fixture owner binding failed");
        maid.setPosition(f.x + 5.5, f.y + 1, f.z + 3.5);
        helper.assertTrue(
            helper.getWorld()
                .spawnEntityInWorld(maid) && MaidSpawnChecks.present(maid),
            "Weapon fixture maid was not actually registered");
        maid.selectBrainProfileForDevelopment(profile);
        helper.assertTrue(
            maid.getBrainCoordinator()
                .getPolicyRuntime() != null
                && maid.getBrainCoordinator()
                    .getPolicyRuntime()
                    .getProfile() == profile,
            "Weapon switch did not bind the requested complete production profile");
        maid.getBrainCoordinator()
            .setSuspended(true);
        var inventory = maid.getMaidInventory();
        var swordItem = Item.itemRegistry.getObject("etfuturum:netherite_sword");
        helper.assertTrue(swordItem instanceof Item, "Actual user EFR netherite sword provider is absent");
        var sword = tagged(new ItemStack((Item) swordItem, 1, 3), "original-efr-sword");
        var bow = tagged(new ItemStack(Items.bow, 1, 33), "original-native-bow");
        inventory.setInventorySlotContents(0, new ItemStack(Items.arrow, 35));
        inventory.setInventorySlotContents(1, new ItemStack(Items.spider_eye));
        inventory.setInventorySlotContents(2, new ItemStack(Blocks.sapling));
        inventory.setInventorySlotContents(5, sword);
        inventory.setInventorySlotContents(MaidInventory.MAIN_HAND, bow);
        inventory.setInventorySlotContents(MaidInventory.OFF_HAND, tagged(new ItemStack(Items.stick), "off-hand"));
        inventory.setInventorySlotContents(MaidInventory.ARMOR_START, new ItemStack(Items.iron_boots));
        var expected = contents(maid);
        var playerBefore = f.player.inventory.writeToNBT(new NBTTagList())
            .copy();
        var container = new EmptyBackpackContainer(f.player.inventory, maid);
        f.player.openContainer = container;
        helper.afterTest(() -> {
            container.onContainerClosed(f.player);
            if (f.player.openContainer == container) f.player.openContainer = f.player.inventoryContainer;
        });
        maid.getItemUseState()
            .tryAcquire(new Object(), bow, MaidItemUseState.Hand.MAIN, MaidItemUseState.Kind.BOW, () -> true);
        helper.assertTrue(
            container.enchantItem(f.player, ContainerMaid.ACTION_ATTACK),
            "Actual task attack button rejected its legitimate owner");
        swapExpected(expected);
        assertContents(helper, maid, expected);
        helper.assertTrue(
            maid.getHeldItem() == sword && inventory.getStackInSlot(5) == bow
                && maid.getItemUseState()
                    .peek() == null,
            "Attack did not move original sword/bow or cancel old item use");
        helper.startSequence()
            .thenIdle(1)
            .thenExecute(() -> {
                helper.assertTrue(
                    container.enchantItem(f.player, ContainerMaid.ACTION_ATTACK),
                    "Repeated same task button was rejected");
                assertContents(helper, maid, expected);
                helper.assertTrue(maid.getHeldItem() == sword, "Suitable current weapon was needlessly replaced");
            })
            .thenIdle(1)
            .thenExecute(() -> {
                helper.assertTrue(
                    container.enchantItem(f.player, ContainerMaid.ACTION_RANGED),
                    "Actual ranged task button was rejected");
                swapExpected(expected);
                assertContents(helper, maid, expected);
                helper.assertTrue(
                    maid.getHeldItem() == bow && inventory.getStackInSlot(5) == sword,
                    "Ranged task did not reverse the exact original whole stacks");
            })
            .thenIdle(1)
            .thenExecute(() -> {
                f.player.setPosition(f.x + 20.5, f.y + 1, f.z + 3.5);
                helper.assertTrue(
                    !container.enchantItem(f.player, ContainerMaid.ACTION_ATTACK)
                        && maid.getTask() == MaidTask.RANGED_ATTACK,
                    "Distant player changed task or equipment");
                assertContents(helper, maid, expected);
                f.player.setPosition(f.x + 5.5, f.y + 1, f.z + 4.5);
                // 本夹具精确取回自身剑，随后验证无合适背包武器时不制造/移走物品
                helper.assertTrue(inventory.getStackInSlot(5) == sword, "Owned sword successor changed");
                inventory.setInventorySlotContents(5, null);
                expected[5] = null;
                helper.assertTrue(
                    container.enchantItem(f.player, ContainerMaid.ACTION_ATTACK),
                    "Missing weapon must not prevent choosing the task itself");
                assertContents(helper, maid, expected);
                helper.assertTrue(maid.getHeldItem() == bow, "Missing weapon path lost the original held item");
                helper.assertTrue(
                    playerBefore.equals(f.player.inventory.writeToNBT(new NBTTagList())),
                    "Task weapon switching changed complete player inventory");
                System.out.println(
                    "TLMNH_TASK_EQUIPMENT_PASS profile=" + profile.getId()
                        + " occupiedMainRoundTrip=true full12Slots=true originalReferences=true fullNbt=true distantDenied=true noWeaponUnchanged=true");
                helper.succeed();
            });
    }

    private static ItemStack tagged(ItemStack stack, String label) {
        var tag = new NBTTagCompound();
        tag.setString("OpaqueTaskSwitch", label);
        tag.setIntArray("PreservedNumbers", new int[] { 2, 8, 5, 1 });
        stack.setTagCompound(tag);
        return stack;
    }

    private static ItemStack[] contents(EntityMaid maid) {
        var result = new ItemStack[MaidInventory.TOTAL_SIZE];
        for (int slot = 0; slot < result.length; slot++) result[slot] = ItemStack.copyItemStack(
            maid.getMaidInventory()
                .getStackInSlot(slot));
        return result;
    }

    private static void swapExpected(ItemStack[] expected) {
        var held = expected[MaidInventory.MAIN_HAND];
        expected[MaidInventory.MAIN_HAND] = expected[5];
        expected[5] = held;
    }

    private static void assertContents(GameTestHelper helper, EntityMaid maid, ItemStack[] expected) {
        for (int slot = 0; slot < expected.length; slot++) helper.assertTrue(
            ItemStack.areItemStacksEqual(
                expected[slot],
                maid.getMaidInventory()
                    .getStackInSlot(slot)),
            "Task switch changed native full slot " + slot);
    }
}
