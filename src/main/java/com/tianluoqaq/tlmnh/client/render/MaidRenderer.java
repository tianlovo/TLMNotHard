package com.tianluoqaq.tlmnh.client.render;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.entity.RenderLiving;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.client.development.HeldItemGpuProbe;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 保留 Forge 生物渲染事件、受伤着色、名字和拴绳路径，模型姿态按女仆隔离 */
@SideOnly(Side.CLIENT)
public final class MaidRenderer extends RenderLiving {

    private static final String DEFAULT_MODEL = "touhou_little_maid:hakurei_reimu";
    private final BuiltinMaidModels models;
    private final MaidHeldItemLayer heldItems = new MaidHeldItemLayer();
    private final Map<EntityMaid, CachedModel> entities = new WeakHashMap<>();
    private final Set<String> failures = new HashSet<>();
    private int generation = -1;

    public MaidRenderer(BuiltinMaidModels models) {
        super(new ModelBase() {}, 0.5F);
        this.models = models;
    }

    @Override
    public void doRender(EntityLiving entity, double x, double y, double z, float yaw, float partialTick) {
        var previous = mainModel;
        try {
            var model = modelFor((EntityMaid) entity);
            model.beginRenderFrame();
            HeldItemGpuProbe.beforeMaidBody((EntityMaid) entity, model);
            mainModel = model;
            super.doRender(entity, x, y, z, yaw, partialTick);
        } finally {
            mainModel = previous;
        }
    }

    @Override
    public void doRenderShadowAndFire(Entity entity, double x, double y, double z, float yaw, float partialTick) {
        float previousShadow = shadowSize;
        try {
            shadowSize = 0.5F * modelFor((EntityMaid) entity).getChoice()
                .getEntityScale();
            super.doRenderShadowAndFire(entity, x, y, z, yaw, partialTick);
        } finally {
            shadowSize = previousShadow;
        }
    }

    private BuiltinMaidModel modelFor(EntityMaid maid) {
        if (generation != models.getGeneration()) {
            entities.clear();
            failures.clear();
            generation = models.getGeneration();
        }
        var cached = entities.get(maid);
        String requested = maid.getModelId();
        if (cached != null && requested.equals(cached.requested)) return cached.model;
        var choice = BuiltinModelCatalog.get(requested);
        if (choice == null) choice = BuiltinModelCatalog.get(DEFAULT_MODEL);
        BuiltinMaidModel model;
        try {
            model = models.create(choice);
        } catch (IOException error) {
            if (failures.add(requested)) TLMNotHard.LOG.error("Cannot render builtin model {}", requested, error);
            try {
                model = models.create(BuiltinModelCatalog.get(DEFAULT_MODEL));
            } catch (IOException fallbackError) {
                fallbackError.addSuppressed(error);
                throw new IllegalStateException("Cannot load the default maid model", fallbackError);
            }
        }
        entities.put(maid, new CachedModel(requested, model));
        return model;
    }

    @Override
    protected ResourceLocation getEntityTexture(Entity entity) {
        return modelFor((EntityMaid) entity).getChoice()
            .getTexture();
    }

    @Override
    protected void preRenderCallback(EntityLivingBase entity, float partialTick) {
        float scale = ((BuiltinMaidModel) mainModel).getChoice()
            .getEntityScale();
        GL11.glScalef(scale, scale, scale);
    }

    @Override
    protected void renderEquippedItems(EntityLivingBase entity, float partialTick) {
        super.renderEquippedItems(entity, partialTick);
        if (entity instanceof EntityMaid maid && mainModel instanceof BuiltinMaidModel model) {
            if (HeldItemGpuProbe.isCapturing(maid) && HeldItemGpuProbe.atEquippedItems(
                maid,
                model,
                partialTick,
                () -> heldItems.render(maid, model, renderManager.itemRenderer, partialTick))) return;
            heldItems.render(maid, model, renderManager.itemRenderer, partialTick);
        }
    }

    public com.google.gson.JsonObject inspectPreviewForDevelopment(EntityMaid maid) {
        if (!Boolean.getBoolean("tlmnh.devCommands")) throw new IllegalStateException("Preview diagnostic disabled");
        var result = new com.google.gson.JsonObject();
        var cached = entities.get(maid);
        if (cached == null) return result;
        result.addProperty("completedPose", cached.model.hasCompletedPoseFor(maid));
        result.addProperty(
            "model",
            cached.model.getChoice()
                .getId());
        for (var side : MaidHeldItemTransforms.Side.values()) {
            var attachment = MaidHandAttachment.capture(cached.model, side);
            result.addProperty(side.name(), attachment.status.name());
            result.addProperty(side.name() + "Chain", attachment.boneChain.toString());
        }
        return result;
    }

    public AutoCloseable openDevelopmentItemStateScope() {
        if (!Boolean.getBoolean("tlmnh.devCommands")) throw new IllegalStateException("Held item probe disabled");
        return new MaidItemRenderScope();
    }

    private static final class CachedModel {

        private final String requested;
        private final BuiltinMaidModel model;

        private CachedModel(String requested, BuiltinMaidModel model) {
            this.requested = requested;
            this.model = model;
        }
    }
}
