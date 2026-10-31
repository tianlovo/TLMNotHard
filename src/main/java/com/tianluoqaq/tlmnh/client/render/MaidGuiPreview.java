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
        var minecraft = Minecraft.getMinecraft();
        var resolution = new ScaledResolution(minecraft, minecraft.displayWidth, minecraft.displayHeight);
        int scale = resolution.getScaleFactor();
        int mode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        int depth = MaidRenderPlatformState.matrixDepth(GL11.GL_MODELVIEW_STACK_DEPTH);
        float viewY = RenderManager.instance.playerViewY;
        float body = maid.renderYawOffset, yaw = maid.rotationYaw, pitch = maid.rotationPitch;
        float head = maid.rotationYawHead, previousHead = maid.prevRotationYawHead;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(
                (left + 6) * scale,
                (resolution.getScaledHeight() - top - 107) * scale,
                67 * scale,
                95 * scale);
            GuiInventory.func_147046_a(left + 40, top + 100, 40, left + 40 - mouseX, top + 50 - mouseY, maid);
        } finally {
            maid.renderYawOffset = body;
            maid.rotationYaw = yaw;
            maid.rotationPitch = pitch;
            maid.rotationYawHead = head;
            maid.prevRotationYawHead = previousHead;
            RenderManager.instance.playerViewY = viewY;
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            while (MaidRenderPlatformState.matrixDepth(GL11.GL_MODELVIEW_STACK_DEPTH) > depth) GL11.glPopMatrix();
            GL11.glPopAttrib();
            GL11.glMatrixMode(mode);
        }
    }
}
