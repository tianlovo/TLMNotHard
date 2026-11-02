/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/renderer/entity/EntityTombstoneRenderer.java, MIT
 * Adaptation: Forge 1.7.10 rendering, owned lifecycle entity and legacy resource reload; no Aether integration
 */
package com.github.tartaricacid.touhoulittlemaid.client.renderer.entity;

import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.tianluoqaq.tlmnh.client.lifecycle.TombstoneModelResources;
import com.tianluoqaq.tlmnh.client.render.LegacyPoseStack;
import com.tianluoqaq.tlmnh.client.render.MaidItemRenderScope;
import com.tianluoqaq.tlmnh.client.render.MaidRenderProtection;
import com.tianluoqaq.tlmnh.client.render.TessellatorVertexConsumer;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class EntityTombstoneRenderer extends Render {

    private static final ResourceLocation DEFAULT_TEXTURE = texture("overworld");
    private static final ResourceLocation NETHER_TEXTURE = texture("the_nether");
    private static final ResourceLocation END_TEXTURE = texture("the_end");
    private final TombstoneModelResources models;

    public EntityTombstoneRenderer(TombstoneModelResources models) {
        this.models = models;
    }

    private static ResourceLocation texture(String dimension) {
        return new ResourceLocation(
            "touhou_little_maid:textures/bedrock/entity/tombstone/tombstone_" + dimension + ".png");
    }

    @Override
    public void doRender(Entity entity, double x, double y, double z, float yaw, float partialTick) {
        if (!MaidRenderProtection.ready()) return;
        IntegrationGuard.call(Side.CLIENT, ExternalFeature.CLIENT_TOMBSTONE, () -> {
            var tombstone = (MaidTombstoneEntity) entity;
            var model = models.get();
            var texture = getEntityTexture(tombstone);
            try (var scope = new MaidItemRenderScope()) {
                GL11.glTranslated(x, y, z);
                GL11.glScalef(-1, -1, 1);
                GL11.glTranslated(0, -1.501, 0);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(GL11.GL_GREATER, .1F);
                GL11.glEnable(GL11.GL_NORMALIZE);
                GL11.glColor4f(1, 1, 1, 1);
                bindTexture(texture);
                model.setupAnim(tombstone, 0, 0, -.1F, 0, 0);
                var vertices = TessellatorVertexConsumer.INSTANCE;
                vertices.begin();
                try {
                    model.renderToBuffer(
                        new LegacyPoseStack(),
                        vertices,
                        tombstone.getBrightnessForRender(partialTick),
                        0,
                        1,
                        1,
                        1,
                        1);
                } finally {
                    vertices.end();
                }
            }
            boolean shown = !tombstone.getMaidName()
                .isEmpty() && renderManager.livingPlayer != null
                && tombstone.getDistanceSqToEntity(renderManager.livingPlayer) < 64 * 64;
            if (shown) {
                renderNameTag(
                    "\u00a76\u00a7n" + StatCollector.translateToLocal("entity.touhou_little_maid.tombstone.display"),
                    x,
                    y + 1.6,
                    z);
                renderNameTag(tombstone.getMaidName(), x, y + 1.85, z);
            }
            return true;
        }, () -> false);
    }

    @Override
    protected ResourceLocation getEntityTexture(Entity entity) {
        int dimension = entity.worldObj.provider.dimensionId;
        if (dimension == -1) return NETHER_TEXTURE;
        if (dimension == 1) return END_TEXTURE;
        var optional = com.tianluoqaq.tlmnh.compat.twilightforest.client.render.TombstoneDimensionTexture
            .resolve(entity.worldObj);
        return optional == null ? DEFAULT_TEXTURE : optional;
    }

    private void renderNameTag(String text, double x, double y, double z) {
        try (var scope = new MaidItemRenderScope()) {
            GL11.glTranslated(x, y, z);
            GL11.glNormal3f(0, 1, 0);
            GL11.glRotatef(-renderManager.playerViewY, 0, 1, 0);
            GL11.glRotatef(renderManager.playerViewX, 1, 0, 0);
            GL11.glScalef(-.025F, -.025F, .025F);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            var font = getFontRendererFromRenderManager();
            font.drawString(text, -font.getStringWidth(text) / 2, 0, 0xFFFFFF);
        }
    }
}
