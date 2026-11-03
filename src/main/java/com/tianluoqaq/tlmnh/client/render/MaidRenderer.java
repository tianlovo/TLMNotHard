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
import net.minecraft.entity.player.EntityPlayer;
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

    private static final float[] SLEEP_ROTATIONS = { 90, 0, 270, 180 };
    private static final String DEFAULT_MODEL = "touhou_little_maid:hakurei_reimu";
    private final BuiltinMaidModels models;
    private final MaidHeldItemLayer heldItems = new MaidHeldItemLayer();
    private final com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.layer.LayerMaidBipedHead headItems = new com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.layer.LayerMaidBipedHead();
    private final com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.layer.LayerMaidBackItem backItems = new com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.layer.LayerMaidBackItem();
    private final Map<EntityMaid, CachedModel> entities = new WeakHashMap<>();
    private final Set<String> failures = new HashSet<>();
    private int generation = -1;
    private final com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.ChatBubbleRenderer chatBubbles = new com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.ChatBubbleRenderer();

    public MaidRenderer(BuiltinMaidModels models) {
        super(new ModelBase() {}, 0.5F);
        this.models = models;
    }

    @Override
    public void doRender(EntityLiving entity, double x, double y, double z, float yaw, float partialTick) {
        MaidRenderProtection.draw(() -> renderBody(entity, x, y, z, yaw, partialTick));
    }

    private void renderBody(EntityLiving entity, double x, double y, double z, float yaw, float partialTick) {
        var previous = mainModel;
        try (var scope = new MaidItemRenderScope()) {
            var model = modelFor((EntityMaid) entity);
            model.beginRenderFrame();
            HeldItemGpuProbe.beforeMaidBody((EntityMaid) entity, model);
            mainModel = model;
            super.doRender(entity, x, y, z, yaw, partialTick);
            if (Boolean.getBoolean("tlmnh.devCommands")) {
                var maid = (EntityMaid) entity;
                var cached = entities.get(maid);
                if (cached != null && cached.model == model
                    && model.hasCompletedPoseFor(maid)
                    && cached.textureTick == maid.ticksExisted) {
                    cached.lastRenderTick = maid.ticksExisted;
                    cached.poseSerial = model.getPoseSerial();
                }
            }
            chatBubbles.render((EntityMaid) entity, x, y, z, partialTick);
        } finally {
            mainModel = previous;
        }
    }

    @Override
    public void doRenderShadowAndFire(Entity entity, double x, double y, double z, float yaw, float partialTick) {
        MaidRenderProtection.draw(() -> renderShadow(entity, x, y, z, yaw, partialTick));
    }

    private void renderShadow(Entity entity, double x, double y, double z, float yaw, float partialTick) {
        float previousShadow = shadowSize;
        try (var scope = new MaidItemRenderScope()) {
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
        var choice = MaidNamedModelOverride.resolve(maid);
        if (choice == null) choice = BuiltinModelCatalog.get(DEFAULT_MODEL);
        String requested = choice.getId();
        if (cached != null && requested.equals(cached.requested)) return cached.model;
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
    protected void bindEntityTexture(Entity entity) {
        super.bindEntityTexture(entity);
        if (Boolean.getBoolean("tlmnh.devCommands") && entity instanceof EntityMaid maid) {
            var cached = entities.get(maid);
            if (cached != null) {
                cached.texture = cached.model.getChoice()
                    .getTexture();
                cached.textureId = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                cached.textureGeneration = generation;
                cached.textureTick = maid.ticksExisted;
            }
        }
    }

    @Override
    protected void rotateCorpse(EntityLivingBase entity, float age, float yaw, float partialTick) {
        var maid = (EntityMaid) entity;
        if (!maid.hasPose(com.tianluoqaq.tlmnh.common.entity.MaidPose.SLEEPING) || maid.deathTime > 0) {
            super.rotateCorpse(entity, age, yaw, partialTick);
            rotateCarriedMaid(maid);
            MaidSwimRenderPose.apply(maid, (BuiltinMaidModel) mainModel, partialTick);
            return;
        }
        int direction = maid.getSleepingBedDirection();
        float rotation = yaw;
        if (direction >= 0) {
            var step = net.minecraft.block.BlockBed.field_149981_a[direction];
            float eyeOffset = com.tianluoqaq.tlmnh.common.entity.MaidEntityPose.STANDING.height
                * (maid.isSitting() ? .65F : .85F) - .1F;
            GL11.glTranslatef(-step[0] * eyeOffset, 0, -step[1] * eyeOffset);
            // 原LivingEntityRenderer：SOUTH90/WEST0/NORTH270/EAST180
            rotation = SLEEP_ROTATIONS[direction];
        }
        GL11.glRotatef(rotation, 0, 1, 0);
        GL11.glRotatef(getDeathMaxRotation(entity), 0, 0, 1);
        GL11.glRotatef(270, 0, 1, 0);
        rotateCarriedMaid(maid);
        MaidSwimRenderPose.apply(maid, (BuiltinMaidModel) mainModel, partialTick);
        com.tianluoqaq.tlmnh.client.development.life.MaidSleepRenderObservation.completed(maid, direction, rotation);
    }

    private void rotateCarriedMaid(EntityMaid maid) {
        if (maid.ridingEntity instanceof EntityPlayer && mainModel instanceof BuiltinMaidModel model
            && !model.getChoice()
                .isGecko()) {
            // 固定上游EntityMaidRenderer.setupRotations：父级旋转后、实体缩放前，附件共用该矩阵
            GL11.glTranslatef(-.375F, .8325F, .375F);
            GL11.glRotatef(65, 0, 0, -1);
            GL11.glRotatef(-80, 0, -1, 0);
        }
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
            headItems.render(maid, model);
            backItems.render(maid, model, renderManager.itemRenderer);
            MaidBannerRenderer.render(maid, model);
        }
    }

    public com.google.gson.JsonObject inspectPreviewForDevelopment(EntityMaid maid) {
        if (!Boolean.getBoolean("tlmnh.devCommands")) throw new IllegalStateException("Preview diagnostic disabled");
        var result = new com.google.gson.JsonObject();
        var cached = entities.get(maid);
        if (cached == null) return result;
        result.addProperty("completedPose", cached.model.hasCompletedPoseFor(maid));
        result.addProperty("texture", cached.texture == null ? null : cached.texture.toString());
        result.addProperty("textureId", cached.textureId);
        result.addProperty("resourceGeneration", cached.textureGeneration);
        result.addProperty("currentResourceGeneration", models.getGeneration());
        result.addProperty("lastTextureTick", cached.textureTick);
        result.addProperty("lastRenderTick", cached.lastRenderTick);
        result.addProperty("poseSerial", cached.poseSerial);
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
        private ResourceLocation texture;
        private int textureId = -1, textureGeneration = -1, textureTick = -1, lastRenderTick = -1;
        private long poseSerial;

        private CachedModel(String requested, BuiltinMaidModel model) {
            this.requested = requested;
            this.model = model;
        }
    }
}
