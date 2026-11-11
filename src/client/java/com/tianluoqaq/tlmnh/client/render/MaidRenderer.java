package com.tianluoqaq.tlmnh.client.render;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.block.BlockBed;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.entity.RenderLiving;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.ChatBubbleRenderer;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.layer.LayerMaidBackItem;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.layer.LayerMaidBipedHead;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.AnimatedGeoBone;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.util.RenderUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.tianluoqaq.tlmnh.client.diagnostic.ClientDevelopmentAccess;
import com.tianluoqaq.tlmnh.client.render.attachment.MaidBannerRenderer;
import com.tianluoqaq.tlmnh.client.render.attachment.MaidHandAttachment;
import com.tianluoqaq.tlmnh.client.render.attachment.MaidHeldItemLayer;
import com.tianluoqaq.tlmnh.client.render.attachment.MaidHeldItemTransforms;
import com.tianluoqaq.tlmnh.client.render.buffer.LegacyPoseStack;
import com.tianluoqaq.tlmnh.client.render.model.BuiltinMaidModel;
import com.tianluoqaq.tlmnh.client.render.model.BuiltinMaidModels;
import com.tianluoqaq.tlmnh.client.render.model.MaidNamedModelOverride;
import com.tianluoqaq.tlmnh.client.render.model.MaidSwimRenderPose;
import com.tianluoqaq.tlmnh.client.render.molang.MaidMolangFrames;
import com.tianluoqaq.tlmnh.client.render.scope.MaidItemRenderScope;
import com.tianluoqaq.tlmnh.client.render.scope.MaidRenderProtection;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.entity.MaidEntityPose;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.foundation.ModContext;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 保留 Forge 生物渲染事件、受伤着色、名字和拴绳路径，模型姿态按女仆隔离 */
@SideOnly(Side.CLIENT)
public final class MaidRenderer extends RenderLiving {

    private static final float[] SLEEP_ROTATIONS = { ClientRenderLayout.MAID_DOMAIN_PARAM, ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X, ClientRenderLayout.MAID_DOMAIN_PARAM_2, ClientRenderLayout.HALF_TURN_DEGREES };

    private final BuiltinMaidModels models;
    private final MaidHeldItemLayer heldItems = new MaidHeldItemLayer();
    private final LayerMaidBipedHead headItems = new LayerMaidBipedHead();
    private final LayerMaidBackItem backItems = new LayerMaidBackItem();
    private final Map<EntityMaid, CachedModel> entities = new WeakHashMap<>();
    private final Set<String> failures = new HashSet<>();
    private int generation = ClientRenderLayout.UNSET_OR_NOT_FOUND;
    private final ChatBubbleRenderer chatBubbles = new ChatBubbleRenderer();

    public MaidRenderer(BuiltinMaidModels models) {
        super(new ModelBase() {}, ClientRenderLayout.HALF_FACTOR_F);
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
            if (ClientDevelopmentAccess.isActive()) {
                ClientDevelopmentAccess.hooks()
                    .heldGpuBeforeMaidBody((EntityMaid) entity, model);
            }
            mainModel = model;
            super.doRender(entity, x, y, z, yaw, partialTick);
            if (DevelopmentFlags.isDevCommandsEnabled()) {
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
            shadowSize = ClientRenderLayout.HALF_FACTOR_F * modelFor((EntityMaid) entity).getChoice()
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
        if (choice == null) choice = BuiltinModelCatalog.get(ClientRenderIds.DEFAULT_MODEL);
        String requested = choice.getId();
        if (cached != null && requested.equals(cached.requested)) return cached.model;
        BuiltinMaidModel model;
        try {
            model = models.create(choice);
        } catch (IOException error) {
            if (failures.add(requested)) ModContext.LOG.error(ClientRenderDiagnostics.MSG_CANNOT_RENDER_BUILTIN_MODEL, requested, error);
            try {
                model = models.create(BuiltinModelCatalog.get(ClientRenderIds.DEFAULT_MODEL));
            } catch (IOException fallbackError) {
                fallbackError.addSuppressed(error);
                throw new IllegalStateException(ClientRenderDiagnostics.MSG_CANNOT_LOAD_THE_DEFAULT_MAID, fallbackError);
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
        if (DevelopmentFlags.isDevCommandsEnabled() && entity instanceof EntityMaid maid) {
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
        if (!maid.hasPose(MaidPose.SLEEPING) || maid.deathTime > 0) {
            super.rotateCorpse(entity, age, yaw, partialTick);
            rotateCarriedMaid(maid);
            MaidSwimRenderPose.apply(maid, (BuiltinMaidModel) mainModel, partialTick);
            return;
        }
        int direction = maid.getSleepingBedDirection();
        float rotation = yaw;
        if (direction >= 0) {
            var step = BlockBed.field_149981_a[direction];
            float eyeOffset = MaidEntityPose.STANDING.height * (maid.isSitting() ? ClientRenderLayout.MAID_ROTATE_CORPSE_FACTOR : ClientRenderLayout.MAID_ROTATE_CORPSE_FACTOR_2) - ClientRenderLayout.MAID_EYE_OFFSET;
            GL11.glTranslatef(-step[ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X] * eyeOffset, ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X, -step[ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL] * eyeOffset);
            // 原LivingEntityRenderer：SOUTH90/WEST0/NORTH270/EAST180
            rotation = SLEEP_ROTATIONS[direction];
        }
        GL11.glRotatef(rotation, ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X, ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL, ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X);
        GL11.glRotatef(getDeathMaxRotation(entity), ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X, ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X, ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL);
        GL11.glRotatef(ClientRenderLayout.MAID_DOMAIN_PARAM_2, ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X, ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL, ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X);
        rotateCarriedMaid(maid);
        MaidSwimRenderPose.apply(maid, (BuiltinMaidModel) mainModel, partialTick);
        if (ClientDevelopmentAccess.isActive()) {
            ClientDevelopmentAccess.hooks()
                .maidSleepRenderCompleted(maid, direction, rotation);
        }
    }

    private void rotateCarriedMaid(EntityMaid maid) {
        if (maid.ridingEntity instanceof EntityPlayer && mainModel instanceof BuiltinMaidModel model
            && !model.getChoice()
                .isGecko()) {
            // 固定上游EntityMaidRenderer.setupRotations：父级旋转后、实体缩放前，附件共用该矩阵
            GL11.glTranslatef(ClientRenderLayout.MAID_ROTATE_CARRIED_MAID_OFFSET_X, ClientRenderLayout.MAID_ROTATE_CARRIED_MAID_OFFSET_Y, ClientRenderLayout.MAID_ROTATE_CARRIED_MAID_OFFSET_Z);
            GL11.glRotatef(ClientRenderLayout.MAID_ROTATE_CARRIED_MAID_ANGLE_DEG, ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X, ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X, ClientRenderLayout.UNSET_OR_NOT_FOUND);
            GL11.glRotatef(ClientRenderLayout.MAID_ROTATE_CARRIED_MAID_ANGLE_DEG_2, ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X, ClientRenderLayout.UNSET_OR_NOT_FOUND, ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X);
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
            if (ClientDevelopmentAccess.isActive() && ClientDevelopmentAccess.hooks()
                .heldGpuIsCapturing(maid)
                && ClientDevelopmentAccess.hooks()
                    .heldGpuAtEquippedItems(
                        maid,
                        model,
                        partialTick,
                        () -> heldItems.render(maid, model, renderManager.itemRenderer, partialTick)))
                return;
            heldItems.render(maid, model, renderManager.itemRenderer, partialTick);
            headItems.render(maid, model);
            backItems.render(maid, model, renderManager.itemRenderer);
            MaidBannerRenderer.render(maid, model);
        }
    }

    public JsonObject inspectPreviewForDevelopment(EntityMaid maid) {
        if (!DevelopmentFlags.isDevCommandsEnabled()) throw new IllegalStateException(ClientRenderDiagnostics.MSG_PREVIEW_DIAGNOSTIC_DISABLED);
        var result = new JsonObject();
        result.addProperty(
            ClientRenderIds.ID_UUID,
            maid.getUniqueID()
                .toString());
        result.addProperty(ClientRenderIds.ID_ENTITYID, maid.getEntityId());
        result.addProperty(ClientRenderIds.ID_NATIVEMODELID, maid.getModelId());
        result.addProperty(ClientRenderIds.ID_HUNGERCOUNTER, maid.getHunger());
        result.addProperty(ClientRenderIds.ID_ANIMATIONFOODLEVEL, MaidMolangFrames.foodLevel(maid.getAttributeMap()));
        result.add(ClientRenderIds.ID_CARRYPOSITION, carryPositionForDevelopment(maid));
        var cached = entities.get(maid);
        if (cached == null) return result;
        result.addProperty(ClientRenderIds.ID_COMPLETEDPOSE, cached.model.hasCompletedPoseFor(maid));
        result.addProperty(ClientRenderIds.ID_TEXTURE, cached.texture == null ? null : cached.texture.toString());
        result.addProperty(ClientRenderIds.ID_TEXTUREID, cached.textureId);
        result.addProperty(ClientRenderIds.ID_RESOURCEGENERATION, cached.textureGeneration);
        result.addProperty(ClientRenderIds.ID_CURRENTRESOURCEGENERATION, models.getGeneration());
        result.addProperty(ClientRenderIds.ID_LASTTEXTURETICK, cached.textureTick);
        result.addProperty(ClientRenderIds.ID_LASTRENDERTICK, cached.lastRenderTick);
        result.addProperty(ClientRenderIds.ID_POSESERIAL, cached.poseSerial);
        if (cached.model.getGecko() != null) {
            var roots = new JsonArray();
            var gecko = cached.model.getGecko();
            for (String name : new String[] { ClientRenderIds.KEY_M_ROOT, ClientRenderIds.KEY_ROOT, ClientRenderIds.KEY_ALL_BODY }) {
                var bone = gecko.bones()
                    .get(name);
                if (bone == null) continue;
                var row = new JsonObject();
                row.addProperty(ClientRenderIds.ID_NAME, name);
                row.addProperty(ClientRenderIds.ID_PIVOT, bone.getPivotX() + ClientRenderIds.COMMA_DELIMITER + bone.getPivotY() + ClientRenderIds.COMMA_DELIMITER + bone.getPivotZ());
                row.addProperty(
                    ClientRenderIds.ID_TRANSLATION,
                    bone.getPositionX() + ClientRenderIds.COMMA_DELIMITER + bone.getPositionY() + ClientRenderIds.COMMA_DELIMITER + bone.getPositionZ());
                row.addProperty(
                    ClientRenderIds.ID_ROTATIONRADIANS,
                    bone.getRotationX() + ClientRenderIds.COMMA_DELIMITER + bone.getRotationY() + ClientRenderIds.COMMA_DELIMITER + bone.getRotationZ());
                var chain = new ArrayList<AnimatedGeoBone>();
                for (var current = bone; current != null;) {
                    chain.add(current);
                    current = current.geoBone()
                        .parent() == null ? null
                            : gecko.bones()
                                .get(
                                    current.geoBone()
                                        .parent()
                                        .name());
                }
                Collections.reverse(chain);
                var poses = new LegacyPoseStack();
                for (var current : chain) RenderUtils.prepMatrixForBone(poses, current);
                var values = new float[ClientRenderLayout.MAID_INSPECT_PREVIEW_FOR_DEVELOPMENT_PARAM];
                poses.last()
                    .pose()
                    .get(values);
                var matrix = new JsonArray();
                for (float entry : values) matrix.add(new JsonPrimitive(entry));
                row.add(ClientRenderIds.ID_GECKOBONEMATRIXCOLUMNMAJOR, matrix);
                roots.add(row);
            }
            result.add(ClientRenderIds.ID_GECKOROOTOBSERVATION, roots);
        }
        result.addProperty(
            ClientRenderIds.ID_MODEL,
            cached.model.getChoice()
                .getId());
        for (var side : MaidHeldItemTransforms.Side.values()) {
            var attachment = MaidHandAttachment.capture(cached.model, side);
            result.addProperty(side.name(), attachment.status.name());
            result.addProperty(side.name() + ClientRenderIds.KEY_CHAIN, attachment.boneChain.toString());
        }
        return result;
    }

    /** 只读原生挂载点；local玩家的眼高原点和remote/server脚底原点分别留实值 */
    private static JsonObject carryPositionForDevelopment(EntityMaid maid) {
        var result = new JsonObject();
        result.addProperty(ClientRenderIds.ID_MAIDY, maid.posY);
        result.addProperty(ClientRenderIds.ID_MAIDBOUNDINGBOXMINY, maid.boundingBox.minY);
        result.addProperty(ClientRenderIds.ID_MAIDBASEYOFFSET, maid.yOffset);
        result.addProperty(ClientRenderIds.ID_MAIDNATIVERIDERYOFFSET, maid.getYOffset());
        if (!(maid.ridingEntity instanceof EntityPlayer carrier)) {
            result.addProperty(ClientRenderIds.ID_CARRIEDBYPLAYER, false);
            return result;
        }
        result.addProperty(ClientRenderIds.ID_CARRIEDBYPLAYER, true);
        result.addProperty(
            ClientRenderIds.ID_CARRIERUUID,
            carrier.getUniqueID()
                .toString());
        result.addProperty(
            ClientRenderIds.ID_CARRIERCLASS,
            carrier.getClass()
                .getName());
        result.addProperty(ClientRenderIds.ID_CARRIERISLOCALPLAYER, carrier == Minecraft.getMinecraft().thePlayer);
        result.addProperty(ClientRenderIds.ID_CARRIERY, carrier.posY);
        result.addProperty(ClientRenderIds.ID_CARRIERYOFFSET, carrier.yOffset);
        result.addProperty(ClientRenderIds.ID_CARRIERYSIZE, carrier.ySize);
        result.addProperty(ClientRenderIds.ID_CARRIERVISUALFEETY, carrier.posY - carrier.yOffset);
        result.addProperty(ClientRenderIds.ID_CARRIERNATIVEFEETDIFFERENCE, carrier.boundingBox.minY - (carrier.posY - carrier.yOffset));
        result.addProperty(ClientRenderIds.ID_CARRIERBOUNDINGBOXMINY, carrier.boundingBox.minY);
        result.addProperty(ClientRenderIds.ID_CARRIERMOUNTEDYOFFSET, carrier.getMountedYOffset());
        double nativeY = carrier.posY + carrier.getMountedYOffset() + maid.getYOffset();
        double feetAnchoredY = carrier.posY - carrier.yOffset + carrier.getMountedYOffset() + maid.yOffset;
        result.addProperty(ClientRenderIds.ID_NATIVERIDERY, nativeY);
        result.addProperty(ClientRenderIds.ID_FEETANCHOREDRIDERY, feetAnchoredY);
        result.addProperty(ClientRenderIds.ID_ACTUALMINUSNATIVEY, maid.posY - nativeY);
        result.addProperty(ClientRenderIds.ID_ACTUALMINUSFEETANCHOREDY, maid.posY - feetAnchoredY);
        var original = EntityMaid.playerCarryPosition(
            carrier.posX,
            carrier.posY,
            carrier.posZ,
            carrier.yOffset,
            carrier.renderYawOffset,
            carrier.isSneaking());
        result.addProperty(ClientRenderIds.ID_CARRIERBODYYAW, carrier.renderYawOffset);
        result.addProperty(ClientRenderIds.ID_CARRIERDESCENDING, carrier.isSneaking());
        result.addProperty(ClientRenderIds.ID_SOURCETLMX, original.xCoord);
        result.addProperty(ClientRenderIds.ID_SOURCETLMY, original.yCoord);
        result.addProperty(ClientRenderIds.ID_SOURCETLMZ, original.zCoord);
        result.addProperty(ClientRenderIds.ID_ACTUALMINUSSOURCETLMX, maid.posX - original.xCoord);
        result.addProperty(ClientRenderIds.ID_ACTUALMINUSSOURCETLMY, maid.posY - original.yCoord);
        result.addProperty(ClientRenderIds.ID_ACTUALMINUSSOURCETLMZ, maid.posZ - original.zCoord);
        return result;
    }

    /** 复用STATUS观察普通世界中的实际女仆，不改变玩家、模型或实体状态 */
    public static JsonArray inspectLoadedForDevelopment() {
        var result = new JsonArray();
        if (!DevelopmentFlags.isDevCommandsEnabled()) return result;
        var minecraft = Minecraft.getMinecraft();
        if (minecraft.theWorld == null || minecraft.thePlayer == null) return result;
        for (Object entity : minecraft.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityMaid maid) || !maid.isEntityAlive()
                || maid.worldObj != minecraft.theWorld
                || maid.getDistanceSqToEntity(minecraft.thePlayer) > ClientRenderLayout.MIN_IMAGE_WIDTH_THRESHOLD) continue;
            var renderer = RenderManager.instance.getEntityRenderObject(maid);
            if (renderer instanceof MaidRenderer maidRenderer)
                result.add(maidRenderer.inspectPreviewForDevelopment(maid));
            if (result.size() >= ClientRenderLayout.MAX_SIZE_THRESHOLD) break;
        }
        return result;
    }

    public AutoCloseable openDevelopmentItemStateScope() {
        if (!DevelopmentFlags.isDevCommandsEnabled()) throw new IllegalStateException(ClientRenderDiagnostics.MSG_HELD_ITEM_PROBE_DISABLED);
        return new MaidItemRenderScope();
    }

    private static final class CachedModel {

        private final String requested;
        private final BuiltinMaidModel model;
        private ResourceLocation texture;
        private int textureId = ClientRenderLayout.UNSET_OR_NOT_FOUND, textureGeneration = ClientRenderLayout.UNSET_OR_NOT_FOUND, textureTick = ClientRenderLayout.UNSET_OR_NOT_FOUND, lastRenderTick = ClientRenderLayout.UNSET_OR_NOT_FOUND;
        private long poseSerial;

        private CachedModel(String requested, BuiltinMaidModel model) {
            this.requested = requested;
            this.model = model;
        }
    }
}
