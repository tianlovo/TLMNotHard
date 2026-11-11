package com.tianluoqaq.tlmnh.common.lifecycle.item;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleService;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

/** 制作的核心尚无归属，首次实际使用后转为玩家专属的一次性魂符 */
public final class MaidCoreItem extends Item {

    public MaidCoreItem() {
        setMaxStackSize(LifecycleItemTuning.SET_MAX_STACK_SIZE_SINGLE_UNIT);
        setUnlocalizedName(LifecycleItemIds.ID_TLMNH_MAID_CORE);
        setTextureName(LifecycleItemIds.ID_TOUHOU_LITTLE_MAID_SMART_SLAB_HAS_MAID);
    }

    @Override
    public boolean onItemUse(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        if (side != LifecycleItemTuning.SET_MAX_STACK_SIZE_SINGLE_UNIT) return false;
        if (world.isRemote) return true;
        var result = IntegrationGuard.call(
            ExternalFeature.ITEM_DELIVERY,
            () -> MaidLifecycleService.activateCore(player, stack, x, y, z),
            () -> MaidLifecycleService.Result.DENIED);
        MaidLifecycleService.tell(player, result);
        return true;
    }
}
