/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/renderer/entity/layer/LayerMaidBackItem.java and geckolayer/GeckoLayerMaidBackItem.java, MIT
 * Adaptation: shared legacy draw helper; core EmptyBackpack offset, no optional content integrations
 */
package com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.layer;

import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.client.render.BuiltinMaidModel;
import com.tianluoqaq.tlmnh.client.render.MaidBackItemRenderer;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class LayerMaidBackItem {

    public void render(EntityMaid maid, BuiltinMaidModel model, ItemRenderer renderer) {
        ItemStack stack = maid.getBackpackShowItem();
        if (stack == null || stack.stackSize <= 0
            || stack.getMaxDamage() <= 0
            || !model.hasCompletedPoseFor(maid)
            || !model.getChoice()
                .isShowBackpack()
            || maid.hasPose(MaidPose.SLEEPING)
            || maid.isInvisible()
            || !maid.getConfigManager()
                .isShowBackItem())
            return;
        MaidBackItemRenderer.render(maid, model, renderer, stack);
    }
}
