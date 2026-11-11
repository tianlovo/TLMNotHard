package com.tianluoqaq.tlmnh.common.inventory;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponCatalog;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.recovery.RecoveryParcel;

import cpw.mods.fml.relauncher.Side;

/** 玩家切换战斗任务时复用原TaskEquipUtil的首个背包武器/原主手整栈互换规则 */
public final class MaidTaskEquipment {

    private static final String GUARD = "equipment/task_switch";
    private static final Set<EntityMaid> SWITCHING = Collections.newSetFromMap(new IdentityHashMap<>());

    private MaidTaskEquipment() {}

    public static boolean equip(EntityPlayer player, EntityMaid maid, MaidTask task) {
        if (maid == null || maid.worldObj == null
            || maid.worldObj.isRemote
            || maid.getCombatHitReceipt()
                .blocksTransfer()
            || task != MaidTask.ATTACK && task != MaidTask.RANGED_ATTACK
            || !IntegrationGuard.available(Side.SERVER, GUARD)
            || !authorized(player, maid, task)
            || !SWITCHING.add(maid)) return false;
        try {
            // 先归还本次用餐借用的原槽，再选武器，不把临时食物/原武器误当背包后继
            maid.getMealController()
                .cancel();
            maid.getShieldController()
                .cancelUse("task_weapon_switch");
            maid.getItemUseState()
                .invalidate();
            if (!authorized(player, maid, task)) return false;
            var inventory = maid.getMaidInventory();
            var before = new Snapshot(inventory);
            if (acceptsFor(maid, task, before.references[MaidInventory.MAIN_HAND]))
                return before.owned(inventory) && authorized(player, maid, task);
            if (!before.owned(inventory) || !authorized(player, maid, task)) return false;
            for (int slot = 0; slot < MaidInventory.BACKPACK_SIZE; slot++) {
                boolean accepted = acceptsFor(maid, task, before.references[slot]);
                // 外部物品属性查询可以同步回调；只移动仍属本次完整快照的真实物品
                if (!before.owned(inventory) || !authorized(player, maid, task)
                    || maid.getItemUseState()
                        .peek() != null)
                    return false;
                if (!accepted) continue;
                return inventory.exchangeBackpackWithMainHand(
                    slot,
                    before.references[slot],
                    before.references[MaidInventory.MAIN_HAND]);
            }
            return false;
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.report(Side.SERVER, GUARD, failure);
            return false;
        } finally {
            SWITCHING.remove(maid);
        }
    }

    private static boolean authorized(EntityPlayer player, EntityMaid maid, MaidTask task) {
        return (maid.getTask() == task
            || maid.isUnifiedCombat() && maid.getTask() == MaidTask.ATTACK && task == MaidTask.RANGED_ATTACK)
            && !maid.getCombatHitReceipt()
                .blocksTransfer()
            && MaidContainerAccess.canModify(player, player, maid);
    }

    private static boolean acceptsFor(EntityMaid maid, MaidTask task, ItemStack stack) {
        if (!maid.isUnifiedCombat()) return accepts(task, stack);
        var profile = MaidWeaponCatalog.inspect(stack);
        return task == MaidTask.RANGED_ATTACK ? profile.bow() : profile.melee();
    }

    static boolean accepts(MaidTask task, ItemStack stack) {
        if (stack == null || stack.getItem() == null
            || stack.getItem() == RecoveryParcel.ITEM
            || stack.stackSize <= 0
            || stack.stackSize > Math.min(64, stack.getMaxStackSize())) return false;
        // 当前生产发射器只支持实际Items.bow；不自动装备尚无发射适配的外部弓
        if (task == MaidTask.RANGED_ATTACK) return stack.getItem() == Items.bow;
        return task == MaidTask.ATTACK && !(stack.getItem() instanceof ItemBow)
            && stack.getAttributeModifiers()
                .containsKey(SharedMonsterAttributes.attackDamage.getAttributeUnlocalizedName());
    }

    private static final class Snapshot {

        final ItemStack[] references = new ItemStack[MaidInventory.TOTAL_SIZE];
        final ItemStack[] contents = new ItemStack[MaidInventory.TOTAL_SIZE];

        Snapshot(MaidInventory inventory) {
            for (int slot = 0; slot < references.length; slot++) {
                references[slot] = inventory.getStackInSlot(slot);
                contents[slot] = ItemStack.copyItemStack(references[slot]);
            }
        }

        boolean owned(MaidInventory inventory) {
            for (int slot = 0; slot < references.length; slot++) {
                var actual = inventory.getStackInSlot(slot);
                if (actual != references[slot] || !ItemStack.areItemStacksEqual(actual, contents[slot])) return false;
            }
            return true;
        }
    }
}
