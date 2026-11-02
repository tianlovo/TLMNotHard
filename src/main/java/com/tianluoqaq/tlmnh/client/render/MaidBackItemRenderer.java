package com.tianluoqaq.tlmnh.client.render;

import java.nio.FloatBuffer;

import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.IItemRenderer;
import net.minecraftforge.client.MinecraftForgeClient;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.util.RenderUtils;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 已完成姿态上的背部附件；原版FIXED图标与原生平台装备渲染器分开处理 */
@SideOnly(Side.CLIENT)
public final class MaidBackItemRenderer {

    private static final java.util.Map<EntityMaid, Integer> LAST_DRAWN = new java.util.WeakHashMap<>();

    public static void beginFrame(EntityMaid maid) {
        if (Boolean.getBoolean("tlmnh.devCommands")) LAST_DRAWN.put(maid, 0);
    }

    public static int drawnCount(EntityMaid maid) {
        return LAST_DRAWN.getOrDefault(maid, 0);
    }

    private static final FloatBuffer MATRIX_BUFFER = BufferUtils.createFloatBuffer(16);

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
            poses.translate(0, 1, .25);
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
        poses.translate(0, .5, -.25);
        poses.translate(0, .625, .2);
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
            MATRIX_BUFFER.position(0);
            GL11.glMultMatrix(MATRIX_BUFFER);
            GL11.glEnable(GL11.GL_NORMALIZE);
            var item = stack.getItem();
            int passes = item.requiresMultipleRenderPasses() ? item.getRenderPasses(stack.getItemDamage()) : 1;
            for (int pass = 0; pass < passes; pass++) {
                if (maid.getBackpackShowItem() != stack) return;
                int color = item.getColorFromItemStack(stack, pass);
                GL11.glColor4f((color >> 16 & 255) / 255F, (color >> 8 & 255) / 255F, (color & 255) / 255F, 1);
                renderer.renderItem(maid, stack, pass);
                if (Boolean.getBoolean("tlmnh.devCommands")) LAST_DRAWN.put(maid, pass + 1);
            }
        }
    }

    private static Matrix4f fixedIconBridge() {
        // MC1.20.1 item/generated FIXED: Y=180, scale=1；旧图标quad为[0,1]，先移到中心
        var nativeIcon = new Matrix4f().translate(0, -.3F, 0)
            .scale(1.5F)
            .rotateY((float) Math.toRadians(50))
            .rotateZ((float) Math.toRadians(335))
            .translate(-.9375F, -.0625F, 0);
        return new Matrix4f().rotateY((float) Math.PI)
            .translate(-.5F, -.5F, .03125F)
            .mul(nativeIcon.invert());
    }
}
