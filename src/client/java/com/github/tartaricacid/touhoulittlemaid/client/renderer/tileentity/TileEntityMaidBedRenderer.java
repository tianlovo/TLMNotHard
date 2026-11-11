/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/renderer/tileentity/TileEntityMaidBedRenderer.java, MIT
 * Adaptation: Forge 1.7.10 TESR, guarded GL/legacy Bedrock submission and reloadable seven-color resources
 */
package com.github.tartaricacid.touhoulittlemaid.client.renderer.tileentity;

import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import com.tianluoqaq.tlmnh.client.diagnostic.ClientDevelopmentAccess;
import com.tianluoqaq.tlmnh.client.life.bed.MaidBedModelResources;
import com.tianluoqaq.tlmnh.client.render.buffer.LegacyPoseStack;
import com.tianluoqaq.tlmnh.client.render.buffer.TessellatorVertexConsumer;
import com.tianluoqaq.tlmnh.client.render.scope.MaidItemRenderScope;
import com.tianluoqaq.tlmnh.client.render.scope.MaidRenderProtection;
import com.tianluoqaq.tlmnh.common.life.bed.MaidBedColor;
import com.tianluoqaq.tlmnh.common.life.bed.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class TileEntityMaidBedRenderer extends TileEntitySpecialRenderer {

    private final MaidBedModelResources models;

    public TileEntityMaidBedRenderer(MaidBedModelResources models) {
        this.models = models;
    }

    @Override
    public void renderTileEntityAt(TileEntity tile, double x, double y, double z, float partialTick) {
        if (!MaidRenderProtection.ready()) return;
        IntegrationGuard.call(Side.CLIENT, ExternalFeature.CLIENT_MAID_BED, () -> {
            var bed = (TileEntityMaidBed) tile;
            var color = bed.getColor();
            var model = models.get(color);
            var texture = new ResourceLocation(
                "touhou_little_maid:textures/bedrock/block/maid_bed/" + color.resourceName + ".png");
            int rotation = bed.getWorldObj()
                .getBlockMetadata(bed.xCoord, bed.yCoord, bed.zCoord) & 3;
            try (var scope = new MaidItemRenderScope()) {
                GL11.glTranslated(x, y, z);
                GL11.glTranslated(.5, 0, .5);
                GL11.glRotatef(-rotation * 90, 0, 1, 0);
                GL11.glTranslated(-.5, 0, -.5);
                GL11.glTranslated(.5, 1.5, -.5);
                GL11.glScalef(-1, -1, 1);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(GL11.GL_GREATER, .1F);
                GL11.glEnable(GL11.GL_NORMALIZE);
                GL11.glDepthMask(true);
                if (color == MaidBedColor.BLUE) {
                    GL11.glEnable(GL11.GL_BLEND);
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                } else GL11.glDisable(GL11.GL_BLEND);
                GL11.glColor4f(1, 1, 1, 1);
                bindTexture(texture);
                int light = bed.getWorldObj()
                    .getLightBrightnessForSkyBlocks(bed.xCoord, bed.yCoord, bed.zCoord, 0);
                var vertices = TessellatorVertexConsumer.INSTANCE;
                vertices.begin();
                try {
                    model.renderToBuffer(new LegacyPoseStack(), vertices, light, 0, 1, 1, 1, 1);
                } finally {
                    vertices.end();
                }
            }
            if (ClientDevelopmentAccess.isActive()) {
                ClientDevelopmentAccess.hooks()
                    .maidBedRenderCompleted(bed, model, texture);
            }
            return true;
        }, () -> false);
    }
}
