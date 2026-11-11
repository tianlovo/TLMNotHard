package com.tianluoqaq.tlmnh.client.lifecycle.shrine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 调用者持有MaidItemRenderScope；原胶片恒常闪光沿目标原生物品的双层效果，不访问真实槽 */
@SideOnly(Side.CLIENT)
public final class ShrineItemRenderEffects {

    private static final ResourceLocation GLINT = new ResourceLocation("textures/misc/enchanted_item_glint.png");

    private ShrineItemRenderEffects() {}

    public static void glint(ItemStack display) {
        if (!display.hasEffect(0)) return;
        GL11.glDepthFunc(GL11.GL_EQUAL);
        GL11.glDisable(GL11.GL_LIGHTING);
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(GLINT);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_COLOR, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO);
        GL11.glColor4f(.38F, .19F, .608F, 1);
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        try {
            for (int pass = 0; pass < 2; pass++) {
                GL11.glPushMatrix();
                try {
                    GL11.glScalef(.125F, .125F, .125F);
                    long period = pass == 0 ? 3000 : 4873;
                    float offset = (float) (Minecraft.getSystemTime() % period) / period * 8;
                    GL11.glTranslatef(pass == 0 ? offset : -offset, 0, 0);
                    GL11.glRotatef(pass == 0 ? -50 : 10, 0, 0, 1);
                    ItemRenderer.renderItemIn2D(Tessellator.instance, 0, 0, 1, 1, 256, 256, 1F / 16F);
                } finally {
                    GL11.glPopMatrix();
                }
            }
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
        }
    }
}
