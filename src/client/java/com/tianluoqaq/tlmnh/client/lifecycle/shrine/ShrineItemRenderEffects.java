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

    private static final ResourceLocation GLINT = new ResourceLocation(ClientShrineIds.TEXTURE_ENCHANTED_ITEM_GLINT);

    private ShrineItemRenderEffects() {}

    public static void glint(ItemStack display) {
        if (!display.hasEffect(ClientShrineTuning.HAS_EFFECT_INITIAL_VALUE)) return;
        GL11.glDepthFunc(GL11.GL_EQUAL);
        GL11.glDisable(GL11.GL_LIGHTING);
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(GLINT);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_COLOR, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO);
        GL11.glColor4f(ClientShrineTuning.SHRINE_ITEM_RED_LEVEL, ClientShrineTuning.SHRINE_ITEM_GREEN_LEVEL, ClientShrineTuning.SHRINE_ITEM_BLUE_LEVEL, ClientShrineTuning.SHRINE_ITEM_ALPHA_LEVEL);
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        try {
            for (int pass = 0; pass < ClientShrineTuning.MIN_PASS_THRESHOLD; pass++) {
                GL11.glPushMatrix();
                try {
                    GL11.glScalef(ClientShrineTuning.SHRINE_ITEM_GLINT_SCALE_X, ClientShrineTuning.SHRINE_ITEM_GLINT_SCALE_X, ClientShrineTuning.SHRINE_ITEM_GLINT_SCALE_X);
                    long period = pass == 0 ? ClientShrineTuning.SHRINE_ITEM_GLINT_PARAM : ClientShrineTuning.SHRINE_ITEM_GLINT_PARAM_2;
                    float offset = (float) (Minecraft.getSystemTime() % period) / period * ClientShrineTuning.SHRINE_ITEM_OFFSET;
                    GL11.glTranslatef(pass == 0 ? offset : -offset, ClientShrineTuning.HAS_EFFECT_INITIAL_VALUE, ClientShrineTuning.HAS_EFFECT_INITIAL_VALUE);
                    GL11.glRotatef(pass == 0 ? ClientShrineTuning.SHRINE_ITEM_GLINT_ANGLE_DEG : ClientShrineTuning.SHRINE_ITEM_GLINT_ANGLE_DEG_2, ClientShrineTuning.HAS_EFFECT_INITIAL_VALUE, ClientShrineTuning.HAS_EFFECT_INITIAL_VALUE, ClientShrineTuning.SHRINE_ITEM_ALPHA_LEVEL);
                    ItemRenderer.renderItemIn2D(Tessellator.instance, ClientShrineTuning.HAS_EFFECT_INITIAL_VALUE, ClientShrineTuning.HAS_EFFECT_INITIAL_VALUE, ClientShrineTuning.SHRINE_ITEM_ALPHA_LEVEL, ClientShrineTuning.SHRINE_ITEM_ALPHA_LEVEL, ClientShrineTuning.SHRINE_ITEM_RENDER_ITEM_IN2_D_SIXTH_PARAM, ClientShrineTuning.SHRINE_ITEM_RENDER_ITEM_IN2_D_SIXTH_PARAM, ClientShrineTuning.SHRINE_ITEM_RENDER_ITEM_IN2_D_ARG8_FACTOR / ClientShrineTuning.SHRINE_ITEM_RENDER_ITEM_IN2_D_ARG8_FACTOR_2);
                } finally {
                    GL11.glPopMatrix();
                }
            }
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
        }
    }
}
