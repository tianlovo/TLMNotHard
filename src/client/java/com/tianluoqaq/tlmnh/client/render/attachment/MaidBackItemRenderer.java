package com.tianluoqaq.tlmnh.client.render.attachment;

import java.nio.FloatBuffer;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.IItemRenderer;
import net.minecraftforge.client.MinecraftForgeClient;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.util.RenderUtils;
import com.tianluoqaq.tlmnh.client.render.buffer.LegacyPoseStack;
import com.tianluoqaq.tlmnh.client.render.model.BuiltinMaidModel;
import com.tianluoqaq.tlmnh.client.render.scope.MaidItemFilterScope;
import com.tianluoqaq.tlmnh.client.render.scope.MaidItemRenderScope;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 已完成姿态上的背部附件；原版FIXED图标与原生平台装备渲染器分开处理 */
@SideOnly(Side.CLIENT)
public final class MaidBackItemRenderer {

    private static final Map<EntityMaid, Integer> LAST_DRAWN = new WeakHashMap<>();

    public static void beginFrame(EntityMaid maid) {
        if (DevelopmentFlags.isDevCommandsEnabled()) LAST_DRAWN.put(maid, AttachmentRenderLayout.PUT_INITIAL_VALUE);
    }

    public static int drawnCount(EntityMaid maid) {
        return LAST_DRAWN.getOrDefault(maid, AttachmentRenderLayout.PUT_INITIAL_VALUE);
    }

    private static final FloatBuffer MATRIX_BUFFER = BufferUtils.createFloatBuffer(AttachmentRenderLayout.BACK_ITEM_CREATE_FLOAT_BUFFER_FIRST_PARAM);

    private MaidBackItemRenderer() {}

    public static Matrix4f attachment(BuiltinMaidModel model) {
        var poses = new LegacyPoseStack();
        if (model.getGecko() != null) {
            poses.last()
                .pose()
                .mul(MaidHeldItemTransforms.geckoModelBase());
            var chain = model.getGecko()
                .backpackBones();
            if (!chain.isEmpty() && RenderUtils.prepMatrixForLocator(poses, chain)) return null;
            poses.translate(AttachmentRenderLayout.PUT_INITIAL_VALUE, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Z);
            poses.last()
                .pose()
                .rotateX((float) Math.PI);
        } else {
            model.getBedrockPose()
                .applyTransform(poses);
            poses.last()
                .pose()
                .rotateZ((float) Math.PI)
                .rotateX((float) Math.PI);
        }
        // LayerMaidBackItem + EmptyBackpack.offsetBackpackItem
        poses.translate(AttachmentRenderLayout.PUT_INITIAL_VALUE, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y_2, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Z_2);
        poses.translate(AttachmentRenderLayout.PUT_INITIAL_VALUE, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y_3, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Z_3);
        var matrix = poses.last()
            .pose();
        return MaidHeldItemTransforms.finite(matrix) && matrix.determinant() != 0 ? new Matrix4f(matrix) : null;
    }

    public static void render(EntityMaid maid, BuiltinMaidModel model, ItemRenderer renderer, ItemStack stack) {
        var matrix = attachment(model);
        if (matrix == null) return;
        var custom = MinecraftForgeClient.getItemRenderer(stack, IItemRenderer.ItemRenderType.EQUIPPED);
        if (custom == null) matrix.mul(fixedIconBridge());
        else matrix.mul(
            MaidHeldItemTransforms.nativeArmEntry(
                MaidHeldItemTransforms.Side.RIGHT,
                stack.getItem()
                    .isFull3D() ? MaidHeldItemTransforms.Profile.FULL_3D : MaidHeldItemTransforms.Profile.FLAT,
                stack.getItem()
                    .shouldRotateAroundWhenRendering()));
        try (var scope = new MaidItemRenderScope(); var filters = new MaidItemFilterScope(stack)) {
            MATRIX_BUFFER.clear();
            matrix.get(MATRIX_BUFFER);
            MATRIX_BUFFER.position(AttachmentRenderLayout.PUT_INITIAL_VALUE);
            GL11.glMultMatrix(MATRIX_BUFFER);
            GL11.glEnable(GL11.GL_NORMALIZE);
            var item = stack.getItem();
            int passes = item.requiresMultipleRenderPasses() ? item.getRenderPasses(stack.getItemDamage()) : AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y;
            for (int pass = 0; pass < passes; pass++) {
                if (maid.getBackpackShowItem() != stack) return;
                int color = item.getColorFromItemStack(stack, pass);
                GL11.glColor4f((color >> AttachmentRenderLayout.BACK_ITEM_CREATE_FLOAT_BUFFER_FIRST_PARAM & AttachmentRenderLayout.UNSIGNED_BYTE_MASK) / AttachmentRenderLayout.COLOR_CHANNEL_MAX_F, (color >> AttachmentRenderLayout.SECOND_BYTE_SHIFT & AttachmentRenderLayout.UNSIGNED_BYTE_MASK) / AttachmentRenderLayout.COLOR_CHANNEL_MAX_F, (color & AttachmentRenderLayout.UNSIGNED_BYTE_MASK) / AttachmentRenderLayout.COLOR_CHANNEL_MAX_F, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y);
                renderer.renderItem(maid, stack, pass);
                if (DevelopmentFlags.isDevCommandsEnabled()) LAST_DRAWN.put(maid, pass + 1);
            }
        }
    }

    private static Matrix4f fixedIconBridge() {
        // MC1.20.1 item/generated FIXED: Y=180, scale=1；旧图标quad为[0,1]，先移到中心
        var nativeIcon = new Matrix4f().translate(AttachmentRenderLayout.PUT_INITIAL_VALUE, AttachmentRenderLayout.BACK_ITEM_FIXED_ICON_BRIDGE_OFFSET_Y, AttachmentRenderLayout.PUT_INITIAL_VALUE)
            .scale(AttachmentRenderLayout.BACK_ITEM_FIXED_ICON_BRIDGE_SCALE_X)
            .rotateY((float) Math.toRadians(AttachmentRenderLayout.BACK_ITEM_TO_RADIANS_FIRST_PARAM))
            .rotateZ((float) Math.toRadians(AttachmentRenderLayout.BACK_ITEM_TO_RADIANS_FIRST_PARAM_2))
            .translate(AttachmentRenderLayout.BACK_ITEM_FIXED_ICON_BRIDGE_OFFSET_X, AttachmentRenderLayout.BACK_ITEM_FIXED_ICON_BRIDGE_OFFSET_Y_2, AttachmentRenderLayout.PUT_INITIAL_VALUE);
        return new Matrix4f().rotateY((float) Math.PI)
            .translate(AttachmentRenderLayout.BACK_ITEM_RENDER_OFFSET_X, AttachmentRenderLayout.BACK_ITEM_RENDER_OFFSET_X, AttachmentRenderLayout.BACK_ITEM_RENDER_OFFSET_Z)
            .mul(nativeIcon.invert());
    }
}
