package com.tianluoqaq.tlmnh.common.lifecycle;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

/** 制作的核心尚无归属，首次实际使用后转为玩家专属的一次性魂符 */
public final class MaidCoreItem extends Item {

    public MaidCoreItem() {
        setMaxStackSize(1);
        setUnlocalizedName("tlmnh.maid_core");
        setTextureName("touhou_little_maid:smart_slab_has_maid");
    }

    @Override
    public boolean onItemUse(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        if (side != 1) return false;
        if (world.isRemote) return true;
        var result = IntegrationGuard.call(
            ExternalFeature.ITEM_DELIVERY,
            () -> MaidLifecycleService.activateCore(player, stack, x, y, z),
            () -> MaidLifecycleService.Result.DENIED);
        MaidLifecycleService.tell(player, result);
        return true;
    }
}
