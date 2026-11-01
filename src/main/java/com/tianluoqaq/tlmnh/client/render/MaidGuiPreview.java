package com.tianluoqaq.tlmnh.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.entity.RenderManager;

import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 沿用原版预览位置和裁剪区，原生旧版渲染结束后恢复实体与摄像机 */
@SideOnly(Side.CLIENT)
public final class MaidGuiPreview {

    private MaidGuiPreview() {}

    public static void draw(EntityMaid maid, int left, int top, int mouseX, int mouseY) {
        draw(maid, left + 40, top + 100, 40, left + 40 - mouseX, top + 50 - mouseY, left + 6, top + 12, 67, 95);
    }

    public static void draw(EntityMaid maid, int x, int y, int size, float lookX, float lookY, int clipX, int clipY,
        int clipWidth, int clipHeight) {
        MaidRenderProtection
            .draw(() -> drawUnchecked(maid, x, y, size, lookX, lookY, clipX, clipY, clipWidth, clipHeight));
    }

    private static void drawUnchecked(EntityMaid maid, int x, int y, int size, float lookX, float lookY, int clipX,
        int clipY, int clipWidth, int clipHeight) {
        var minecraft = Minecraft.getMinecraft();
        var resolution = new ScaledResolution(minecraft, minecraft.displayWidth, minecraft.displayHeight);
        int scale = resolution.getScaleFactor();
        float viewY = RenderManager.instance.playerViewY;
        float body = maid.renderYawOffset, yaw = maid.rotationYaw, pitch = maid.rotationPitch;
        float head = maid.rotationYawHead, previousHead = maid.prevRotationYawHead;
        try (var scope = new MaidItemRenderScope()) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(
                clipX * scale,
                (resolution.getScaledHeight() - clipY - clipHeight) * scale,
                clipWidth * scale,
                clipHeight * scale);
            GuiInventory.func_147046_a(x, y, size, lookX, lookY, maid);
        } finally {
            maid.renderYawOffset = body;
            maid.rotationYaw = yaw;
            maid.rotationPitch = pitch;
            maid.rotationYawHead = head;
            maid.prevRotationYawHead = previousHead;
            RenderManager.instance.playerViewY = viewY;
        }
    }
}
