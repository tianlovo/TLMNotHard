/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/renderer/tileentity/TileEntityShrineRenderer.java, MIT
 * Adaptation: Forge 1.7.10 TESR, original generated-item ground transform and display-only storage kind
 */
package com.github.tartaricacid.touhoulittlemaid.client.renderer.tileentity;

import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import com.tianluoqaq.tlmnh.client.lifecycle.shrine.ShrineModelResources;
import com.tianluoqaq.tlmnh.client.render.LegacyPoseStack;
import com.tianluoqaq.tlmnh.client.render.MaidItemRenderScope;
import com.tianluoqaq.tlmnh.client.render.MaidRenderProtection;
import com.tianluoqaq.tlmnh.client.render.TessellatorVertexConsumer;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineBlock;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineTile;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class TileEntityShrineRenderer extends TileEntitySpecialRenderer {

    private static final ResourceLocation TEXTURE = new ResourceLocation(
        "touhou_little_maid:textures/bedrock/block/shrine.png");
    private final ShrineModelResources models;

    public TileEntityShrineRenderer(ShrineModelResources models) {
        this.models = models;
    }

    @Override
    public void renderTileEntityAt(TileEntity tile, double x, double y, double z, float partialTick) {
        if (!MaidRenderProtection.ready()) return;
        IntegrationGuard.call(Side.CLIENT, ExternalFeature.CLIENT_SHRINE, () -> {
            var shrine = (MaidShrineTile) tile;
            int metadata = shrine.getWorldObj()
                .getBlockMetadata(shrine.xCoord, shrine.yCoord, shrine.zCoord);
            if (metadata > 3) throw new IllegalStateException("Unknown shrine orientation retained");
            var model = models.get();
            try (var scope = new MaidItemRenderScope()) {
                GL11.glTranslated(x + .5, y + 1.5, z + .5);
                GL11.glRotatef(-180, 0, 0, 1);
                GL11.glRotatef(-(180 - MaidShrineBlock.facingDirection(metadata) * 90), 0, 1, 0);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(GL11.GL_GREATER, .1F);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glEnable(GL11.GL_NORMALIZE);
                GL11.glDepthMask(true);
                GL11.glColor4f(1, 1, 1, 1);
                bindTexture(TEXTURE);
                int light = shrine.getWorldObj()
                    .getLightBrightnessForSkyBlocks(shrine.xCoord, shrine.yCoord, shrine.zCoord, 0);
                var vertices = TessellatorVertexConsumer.INSTANCE;
                vertices.begin();
                try {
                    model.renderToBuffer(new LegacyPoseStack(), vertices, light, 0, 1, 1, 1, 1);
                } finally {
                    vertices.end();
                }
            }
            var display = shrine.displayItem();
            if (display != null) try (var scope = new MaidItemRenderScope()) {
                GL11.glTranslated(x + .5, y + .85, z + .5);
                GL11.glScalef(.5F, .5F, .5F);
                float degrees = (shrine.getWorldObj()
                    .getTotalWorldTime() + partialTick) % 360;
                GL11.glRotatef(-degrees, 0, 1, 0);
                // 原1.20.1 item/generated的GROUND为translation(0,2,0)/16与scale(.5)
                GL11.glTranslatef(0, .125F, 0);
                GL11.glScalef(.5F, .5F, .5F);
                GL11.glTranslatef(-.5F, -.5F, 1F / 32F);
                bindTexture(TextureMap.locationItemsTexture);
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(GL11.GL_GREATER, .1F);
                GL11.glEnable(GL11.GL_CULL_FACE);
                GL11.glEnable(GL11.GL_NORMALIZE);
                GL11.glColor4f(1, 1, 1, 1);
                var icon = display.getItem()
                    .getIconIndex(display);
                if (icon == null) throw new IllegalStateException("Shrine display item icon missing");
                ItemRenderer.renderItemIn2D(
                    Tessellator.instance,
                    icon.getMaxU(),
                    icon.getMinV(),
                    icon.getMinU(),
                    icon.getMaxV(),
                    icon.getIconWidth(),
                    icon.getIconHeight(),
                    1F / 16F);
                com.tianluoqaq.tlmnh.client.lifecycle.shrine.ShrineItemRenderEffects.glint(display);
            }
            com.tianluoqaq.tlmnh.client.development.life.ShrineRenderObservation
                .completed(shrine, model, display != null);
            return true;
        }, () -> false);
    }
}
