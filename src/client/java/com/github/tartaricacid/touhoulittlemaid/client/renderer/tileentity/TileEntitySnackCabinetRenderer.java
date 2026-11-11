/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/renderer/tileentity/TileEntitySnackCabinetRenderer.java, MIT
 * Adaptation: Forge 1.7.10 TESR, independent GL protection and reloadable static Bedrock resources
 */
package com.github.tartaricacid.touhoulittlemaid.client.renderer.tileentity;

import com.tianluoqaq.tlmnh.common.life.cabinet.SnackCabinetLayout;

import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import com.tianluoqaq.tlmnh.client.diagnostic.ClientDevelopmentAccess;
import com.tianluoqaq.tlmnh.client.life.cabinet.MaidCabinetModelResources;
import com.tianluoqaq.tlmnh.client.render.buffer.LegacyPoseStack;
import com.tianluoqaq.tlmnh.client.render.buffer.TessellatorVertexConsumer;
import com.tianluoqaq.tlmnh.client.render.scope.MaidItemRenderScope;
import com.tianluoqaq.tlmnh.client.render.scope.MaidRenderProtection;
import com.tianluoqaq.tlmnh.common.life.cabinet.BlockSnackCabinet;
import com.tianluoqaq.tlmnh.common.life.cabinet.TileEntitySnackCabinet;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class TileEntitySnackCabinetRenderer extends TileEntitySpecialRenderer {

    private static final ResourceLocation TEXTURE = new ResourceLocation(
        "touhou_little_maid:textures/bedrock/block/snack_cabinet.png");
    private final MaidCabinetModelResources models;

    public TileEntitySnackCabinetRenderer(MaidCabinetModelResources models) {
        this.models = models;
    }

    @Override
    public void renderTileEntityAt(TileEntity tile, double x, double y, double z, float partialTick) {
        if (!MaidRenderProtection.ready()) return;
        IntegrationGuard.call(Side.CLIENT, ExternalFeature.CLIENT_SNACK_CABINET, () -> {
            var cabinet = (TileEntitySnackCabinet) tile;
            int metadata = cabinet.getWorldObj()
                .getBlockMetadata(cabinet.xCoord, cabinet.yCoord, cabinet.zCoord);
            int type = BlockSnackCabinet.cabinetType(metadata);
            if (type > SnackCabinetLayout.TYPE_HALF)
                throw new IllegalStateException("Unknown cabinet display state retained");
            var model = models.get();
            model.getPart("full").visible = type == SnackCabinetLayout.TYPE_FULL;
            model.getPart("half").visible = type == SnackCabinetLayout.TYPE_HALF;
            try (var scope = new MaidItemRenderScope()) {
                GL11.glTranslated(x + .5, y + 1.5, z + .5);
                GL11.glRotatef(-180, 0, 0, 1);
                GL11.glRotatef(-(180 - BlockSnackCabinet.facingDirection(metadata) * 90), 0, 1, 0);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(GL11.GL_GREATER, .1F);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glEnable(GL11.GL_NORMALIZE);
                GL11.glDepthMask(true);
                GL11.glColor4f(1, 1, 1, 1);
                bindTexture(TEXTURE);
                int light = cabinet.getWorldObj()
                    .getLightBrightnessForSkyBlocks(cabinet.xCoord, cabinet.yCoord, cabinet.zCoord, 0);
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
                    .cabinetRenderCompleted(
                        cabinet,
                        model,
                        type,
                        model.getPart("full").visible,
                        model.getPart("half").visible);
            }
            return true;
        }, () -> false);
    }
}
