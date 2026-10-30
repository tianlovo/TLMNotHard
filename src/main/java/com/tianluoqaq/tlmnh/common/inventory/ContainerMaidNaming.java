/*
 * Copyright (c) 2019-2025 tartaric_acid, for the code part
 * Adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: network/message/SendNameTagMessage.java, MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: server container owns one naming session and rechecks access before consuming a tag
 */
package com.tianluoqaq.tlmnh.common.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.interaction.MaidInteractions;
import com.tianluoqaq.tlmnh.common.interaction.MaidNameRules;

/** 名称提交绑定一次开窗和原命名牌，不能操作背包槽或复用旧窗口请求 */
public final class ContainerMaidNaming extends Container {

    private final EntityMaid maid;
    private final EntityPlayer viewer;
    private final long session;
    private final int heldSlot;
    private final ItemStack openedTag;
    private final ItemStack tagSnapshot;
    private boolean submitted;

    public ContainerMaidNaming(InventoryPlayer inventory, EntityMaid maid, long session) {
        viewer = inventory.player;
        this.maid = maid;
        this.session = session;
        heldSlot = inventory.currentItem;
        openedTag = inventory.getCurrentItem();
        tagSnapshot = openedTag == null ? null : openedTag.copy();
    }

    public EntityMaid getMaid() {
        return maid;
    }

    public long getSession() {
        return session;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        if (submitted || session == 0
            || !MaidContainerAccess.canInteract(viewer, player, maid)
            || !MaidInteractions.canInteract(maid, player)
            || !MaidInteractions.isMainHand(player)
            || player.inventory.currentItem != heldSlot
            || player.inventory.getItemStack() != null) return false;
        var held = player.getCurrentEquippedItem();
        return isUnwrittenTag(held) && ItemStack.areItemStackTagsEqual(held, tagSnapshot)
            && (maid.worldObj.isRemote || held == openedTag);
    }

    public static boolean isUnwrittenTag(ItemStack stack) {
        return stack != null && stack.stackSize > 0 && stack.getItem() == Items.name_tag && !stack.hasDisplayName();
    }

    public boolean submit(EntityPlayer player, String name, boolean alwaysShow) {
        if (maid.worldObj.isRemote || player.openContainer != this
            || !canInteractWith(player)
            || !MaidNameRules.isValid(name)) return false;
        submitted = true;
        maid.setCustomNameTag(name);
        maid.setAlwaysRenderNameTag(alwaysShow);
        maid.func_110163_bv();
        if (!player.capabilities.isCreativeMode) {
            var remaining = openedTag.copy();
            remaining.stackSize--;
            player.inventory.setInventorySlotContents(heldSlot, remaining.stackSize > 0 ? remaining : null);
            player.inventory.markDirty();
            player.inventoryContainer.detectAndSendChanges();
        }
        return true;
    }

    @Override
    public ItemStack slotClick(int slot, int button, int mode, EntityPlayer player) {
        return null;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        return null;
    }
}
