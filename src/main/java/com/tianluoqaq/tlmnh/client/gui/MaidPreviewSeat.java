package com.tianluoqaq.tlmnh.client.gui;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 详情页低凳的骑乘锚点，不登记进世界或实体注册表 */
@SideOnly(Side.CLIENT)
public final class MaidPreviewSeat extends EntityLivingBase {

    public MaidPreviewSeat(World world) {
        super(world);
    }

    @Override
    public ItemStack getHeldItem() {
        return null;
    }

    @Override
    public ItemStack getEquipmentInSlot(int slot) {
        return null;
    }

    @Override
    public void setCurrentItemOrArmor(int slot, ItemStack stack) {}

    @Override
    public ItemStack[] getLastActiveItems() {
        return new ItemStack[5];
    }
}
