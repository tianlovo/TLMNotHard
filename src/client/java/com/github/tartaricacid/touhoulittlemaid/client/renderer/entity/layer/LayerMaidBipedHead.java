/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/renderer/entity/layer/LayerMaidBipedHead.java and geckolayer/GeckoLayerMaidBipedHead.java, MIT
 * Adaptation: shared legacy head locator/native skull and block renderer; no optional hat integrations
 */
package com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.layer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.client.render.BuiltinMaidModel;
import com.tianluoqaq.tlmnh.client.render.MaidHeadItemRenderer;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class LayerMaidBipedHead {

    public void render(EntityMaid maid, BuiltinMaidModel model) {
        MaidHeadItemRenderer.beginFrame(maid);
        if (!model.hasCompletedPoseFor(maid)) return;
        IntegrationGuard.call(Side.CLIENT, ExternalFeature.CLIENT_HEAD_ITEMS, () -> {
            MaidHeadItemRenderer.render(maid, model);
            return true;
        }, () -> false);
    }
}
