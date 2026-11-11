/*
 * Attachment transforms adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/renderer/entity/layer/LayerMaidBanner.java and entity/geckolayer/GeckoLayerMaidBanner.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Forge 1.7.10 vertex path and native Et Futurum composite banner texture
 */
package com.tianluoqaq.tlmnh.client.render.attachment;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockPart;
import com.github.tartaricacid.touhoulittlemaid.client.model.bedrock.SimpleBedrockModel;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.util.RenderUtils;
import com.tianluoqaq.tlmnh.client.compat.ClientCompatServices;
import com.tianluoqaq.tlmnh.client.render.buffer.LegacyPoseStack;
import com.tianluoqaq.tlmnh.client.render.buffer.TessellatorVertexConsumer;
import com.tianluoqaq.tlmnh.client.render.model.BuiltinMaidModel;
import com.tianluoqaq.tlmnh.client.render.scope.MaidItemRenderScope;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 固定原LayerMaidBanner及GeckoLayerMaidBanner的背旗定位，图案使用实际EFR提供方纹理 */
@SideOnly(Side.CLIENT)
public final class MaidBannerRenderer {

    private static final ResourceLocation TEXTURE = new ResourceLocation(
        AttachmentRenderIds.TEXTURE_MAID_BANNER);
    private static final Map<EntityMaid, Integer> LAST_DRAWN = new WeakHashMap<>();

    private MaidBannerRenderer() {}

    public static int drawnCount(EntityMaid maid) {
        return LAST_DRAWN.getOrDefault(maid, AttachmentRenderLayout.PUT_INITIAL_VALUE);
    }

    public static void render(EntityMaid maid, BuiltinMaidModel model) {
        if (DevelopmentFlags.isDevCommandsEnabled()) LAST_DRAWN.put(maid, AttachmentRenderLayout.PUT_INITIAL_VALUE);
        var stack = maid.getBackpackShowItem();
        if (!ClientCompatServices.itemRender()
            .isBanner(stack) || stack.stackSize <= 0
            || !model.hasCompletedPoseFor(maid)
            || !model.getChoice()
                .isShowBackpack()
            || maid.hasPose(MaidPose.SLEEPING)
            || maid.isInvisible()
            || !maid.getConfigManager()
                .isShowBackItem())
            return;
        IntegrationGuard.call(Side.CLIENT, ExternalFeature.CLIENT_BANNER, () -> {
            var poses = attachment(model);
            if (poses == null) return false;
            var banner = MaidBannerModelResources.INSTANCE.get();
            int light = maid.getBrightnessForRender(model.getPosedPartialTick());
            try (var scope = new MaidItemRenderScope()) {
                // 原生动态纹理首次上传也会改变GL绑定，因此须位于与绘制相同的恢复边界内
                var pattern = ClientCompatServices.itemRender()
                    .bannerTexture(stack);
                if (pattern == null || maid.getBackpackShowItem() != stack) return false;
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glEnable(GL11.GL_NORMALIZE);
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(GL11.GL_GREATER, AttachmentRenderLayout.BANNER_GL_ALPHA_FUNC_SECOND_FACTOR);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glColor4f(AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y);
                Minecraft.getMinecraft()
                    .getTextureManager()
                    .bindTexture(TEXTURE);
                draw(banner, null, poses, light);
                Minecraft.getMinecraft()
                    .getTextureManager()
                    .bindTexture(pattern);
                draw(banner, banner.getPart(AttachmentRenderIds.ID_BANNER), poses, light);
                if (DevelopmentFlags.isDevCommandsEnabled()) LAST_DRAWN.put(maid, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y);
            }
            return true;
        }, () -> false);
    }

    private static LegacyPoseStack attachment(BuiltinMaidModel model) {
        var poses = new LegacyPoseStack();
        if (model.getGecko() == null) {
            model.getBedrockPose()
                .applyTransform(poses);
            poses.translate(AttachmentRenderLayout.PUT_INITIAL_VALUE, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y_2, AttachmentRenderLayout.BANNER_ATTACHMENT_OFFSET_Z);
            poses.scale(AttachmentRenderLayout.BANNER_ATTACHMENT_SCALE_X, AttachmentRenderLayout.BANNER_ATTACHMENT_SCALE_X, AttachmentRenderLayout.BANNER_ATTACHMENT_SCALE_X);
        } else {
            var bones = model.getGecko()
                .backpackBones();
            if (bones.isEmpty()) return null;
            poses.translate(AttachmentRenderLayout.PUT_INITIAL_VALUE, AttachmentRenderLayout.BANNER_ATTACHMENT_OFFSET_Y, AttachmentRenderLayout.PUT_INITIAL_VALUE);
            poses.scale(AttachmentRenderLayout.UNSET_OR_NOT_FOUND, AttachmentRenderLayout.UNSET_OR_NOT_FOUND, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y);
            if (RenderUtils.prepMatrixForLocator(poses, bones)) return null;
            poses.translate(AttachmentRenderLayout.PUT_INITIAL_VALUE, AttachmentRenderLayout.BANNER_ATTACHMENT_OFFSET_Y_2, AttachmentRenderLayout.BANNER_ATTACHMENT_OFFSET_Z_2);
            poses.scale(AttachmentRenderLayout.BANNER_ATTACHMENT_SCALE_X_2, AttachmentRenderLayout.BANNER_ATTACHMENT_SCALE_Y, AttachmentRenderLayout.BANNER_ATTACHMENT_SCALE_Y);
            poses.mulPose(new Quaternionf().rotationY((float) -Math.PI));
        }
        poses.mulPose(new Quaternionf().rotationX((float) Math.toRadians(AttachmentRenderLayout.BANNER_TO_RADIANS_FIRST_PARAM)));
        var matrix = poses.last()
            .pose();
        return MaidHeldItemTransforms.finite(matrix) && matrix.determinant() != 0 ? poses : null;
    }

    private static void draw(SimpleBedrockModel<?> model, BedrockPart part, LegacyPoseStack poses, int light) {
        var consumer = TessellatorVertexConsumer.INSTANCE;
        consumer.begin();
        try {
            if (part == null) model.renderToBuffer(poses, consumer, light, AttachmentRenderLayout.PUT_INITIAL_VALUE, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y);
            else part.render(poses, consumer, light, AttachmentRenderLayout.PUT_INITIAL_VALUE, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y);
        } finally {
            consumer.end();
        }
    }
}
