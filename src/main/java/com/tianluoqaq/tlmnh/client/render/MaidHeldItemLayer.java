package com.tianluoqaq.tlmnh.client.render;

import java.nio.FloatBuffer;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.client.IItemRenderer;
import net.minecraftforge.client.MinecraftForgeClient;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.client.render.MaidHandAttachment.Attachment;
import com.tianluoqaq.tlmnh.client.render.MaidHeldItemTransforms.Profile;
import com.tianluoqaq.tlmnh.client.render.MaidHeldItemTransforms.Side;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldItems;

import cpw.mods.fml.relauncher.SideOnly;

/** 本体之后复用同一姿态，通过目标整包的原生EQUIPPED入口绘制实际装备 */
@SideOnly(cpw.mods.fml.relauncher.Side.CLIENT)
public final class MaidHeldItemLayer {

    public interface Observer {

        default void hand(HandSnapshot hand) {}

        default void beforeNativeCall(NativeCall call) {}

        default void afterNativeCall(NativeCall call, boolean entered, boolean completed, String failure) {}

        default void skippedByDevelopmentGate(NativeCall call, String transaction) {}
    }

    /** 只含scalar、enum、字符串集合和矩阵副本，不暴露可变骨骼、物品或实体 */
    public static final class HandSnapshot {

        public final String actor, requestedModel, actualModel;
        public final int entityTick;
        public final long poseSerial;
        public final float renderPartialTick, posePartialTick;
        public final boolean completedBodyPose, hasItem;
        public final Attachment attachment;

        private HandSnapshot(EntityMaid maid, BuiltinMaidModel model, float partialTick, ItemStack stack,
            Attachment attachment) {
            actor = maid.getUniqueID()
                .toString();
            requestedModel = maid.getModelId();
            actualModel = model.getChoice()
                .getId();
            entityTick = maid.ticksExisted;
            poseSerial = model.getPoseSerial();
            renderPartialTick = partialTick;
            posePartialTick = model.getPosedPartialTick();
            completedBodyPose = model.hasCompletedPoseFor(maid);
            hasItem = stack != null && stack.stackSize > 0 && stack.getItem() != null;
            this.attachment = attachment;
        }
    }

    public static final class NativeCall {

        public final long sequence;
        public final HandSnapshot hand;
        public final Profile profile;
        public final int pass, stackSize, itemDamage;
        public final String itemId, itemClass, stackNbt, selectedRendererClass;
        public final boolean genericMirrored;
        public final float[] sourceMatrix, bridgeMatrix, nativeEntryMatrix;

        private NativeCall(long sequence, HandSnapshot hand, Profile profile, int pass, ItemStack stack,
            IItemRenderer selectedRenderer, Matrix4f source, Matrix4f bridge) {
            this.sequence = sequence;
            this.hand = hand;
            this.profile = profile;
            this.pass = pass;
            stackSize = stack.stackSize;
            itemDamage = stack.getItemDamage();
            itemId = MaidShieldItems.itemId(stack);
            itemClass = stack.getItem()
                .getClass()
                .getName();
            var nbt = new NBTTagCompound();
            stack.writeToNBT(nbt);
            stackNbt = nbt.toString();
            selectedRendererClass = selectedRenderer == null ? ""
                : selectedRenderer.getClass()
                    .getName();
            genericMirrored = hand.attachment.side == Side.LEFT && profile != Profile.SHIELD_LEFT;
            sourceMatrix = source.get(new float[16]);
            bridgeMatrix = bridge.get(new float[16]);
            var actual = BufferUtils.createFloatBuffer(16);
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, actual);
            nativeEntryMatrix = new float[16];
            actual.get(nativeEntryMatrix);
        }
    }

    public static final class NativeCounters {

        public final long attempts, returnedNormally;

        private NativeCounters() {
            attempts = nativeAttempts;
            returnedNormally = nativeCompleted;
        }
    }

    private static volatile Observer observer;
    private static volatile DevelopmentDrawGate developmentDrawGate;
    private static long nativeSequence, nativeAttempts, nativeCompleted;
    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    private boolean rendering;

    /** 单次客户端开发事务的精确手部故障，不替换任何物品renderer注册 */
    public static final class DevelopmentDrawGate {

        private final String actor, transaction;
        private final Side side;
        private final Thread owner = Thread.currentThread();
        private boolean skipping;
        private long suppressed;

        private DevelopmentDrawGate(String actor, Side side, String transaction) {
            this.actor = actor;
            this.side = side;
            this.transaction = transaction;
        }

        public long getSuppressedCalls() {
            return suppressed;
        }
    }

    public static synchronized DevelopmentDrawGate installDevelopmentDrawGate(String actor, Side side,
        String transaction) {
        if (!Boolean.getBoolean("tlmnh.devCommands")) throw new IllegalStateException("Held item gate disabled");
        if (developmentDrawGate != null || actor == null
            || side == null
            || transaction == null
            || !transaction.matches("[A-Za-z0-9_-]{1,100}\\.json"))
            throw new IllegalStateException("Invalid or already active held item gate");
        java.util.UUID.fromString(actor);
        return developmentDrawGate = new DevelopmentDrawGate(actor, side, transaction);
    }

    public static synchronized void setDevelopmentDrawSkip(DevelopmentDrawGate expected, boolean skipping) {
        if (!Boolean.getBoolean("tlmnh.devCommands") || expected == null
            || developmentDrawGate != expected
            || expected.owner != Thread.currentThread()) throw new IllegalStateException("Unowned held item gate");
        expected.skipping = skipping;
    }

    public static synchronized boolean clearDevelopmentDrawGate(DevelopmentDrawGate expected) {
        if (expected == null || developmentDrawGate != expected || expected.owner != Thread.currentThread())
            return false;
        developmentDrawGate = null;
        return true;
    }

    public static synchronized void setObserverForDevelopment(Observer next) {
        if (!Boolean.getBoolean("tlmnh.devCommands")) throw new IllegalStateException("Held item observer disabled");
        if (next == null) throw new IllegalArgumentException("Observer must not be null");
        if (observer != null && observer != next)
            throw new IllegalStateException("Held item observer already installed");
        observer = next;
    }

    public static synchronized boolean clearObserverForDevelopment(Observer expected) {
        if (observer != expected) return false;
        observer = null;
        return true;
    }

    /** 计数仅表示实际ItemRenderer调用和正常返回，不表示GPU提交或像素通过 */
    public static NativeCounters getNativeCounters() {
        return new NativeCounters();
    }

    public void render(EntityMaid maid, BuiltinMaidModel model, ItemRenderer renderer, float partialTick) {
        if (rendering) throw new IllegalStateException("Nested maid held item layer");
        rendering = true;
        try {
            renderHand(maid, model, renderer, partialTick, Side.RIGHT, maid.getHeldItem());
            renderHand(maid, model, renderer, partialTick, Side.LEFT, maid.getOffhandItem());
        } finally {
            rendering = false;
        }
    }

    private void renderHand(EntityMaid maid, BuiltinMaidModel model, ItemRenderer renderer, float partialTick,
        Side side, ItemStack stack) {
        var observing = observer;
        if (!model.hasCompletedPoseFor(maid)) return;
        if (observing == null && (stack == null || stack.stackSize <= 0 || stack.getItem() == null)) return;
        var attachment = MaidHandAttachment.capture(model, side);
        var hand = observing == null && developmentDrawGate == null ? null
            : new HandSnapshot(maid, model, partialTick, stack, attachment);
        if (observing != null) observing.hand(hand);
        if (attachment.status != MaidHandAttachment.Status.READY || stack == null
            || stack.stackSize <= 0
            || stack.getItem() == null) return;
        try (var scope = new MaidItemRenderScope(); var filters = new MaidItemFilterScope(stack)) {
            var selectedRenderer = MinecraftForgeClient.getItemRenderer(stack, IItemRenderer.ItemRenderType.EQUIPPED);
            Profile profile = selectProfile(side, stack, selectedRenderer);
            var source = attachment.getSourceMatrix();
            var bridge = MaidHeldItemTransforms.bridge(
                side,
                profile,
                profile == Profile.FULL_3D && stack.getItem()
                    .shouldRotateAroundWhenRendering());
            var entry = new Matrix4f(source).mul(bridge);
            matrixBuffer.clear();
            entry.get(matrixBuffer);
            matrixBuffer.position(0);
            GL11.glMultMatrix(matrixBuffer);
            if (side == Side.LEFT && profile != Profile.SHIELD_LEFT) {
                int previousFront = GL11.glGetInteger(GL11.GL_FRONT_FACE);
                GL11.glFrontFace(previousFront == GL11.GL_CW ? GL11.GL_CCW : GL11.GL_CW);
            }
            GL11.glEnable(GL11.GL_NORMALIZE);
            var item = stack.getItem();
            int passes = item.requiresMultipleRenderPasses() ? item.getRenderPasses(stack.getItemDamage()) : 1;
            for (int pass = 0; pass < passes; pass++) {
                if ((side == Side.LEFT ? maid.getOffhandItem() : maid.getHeldItem()) != stack) return;
                int color = item.getColorFromItemStack(stack, pass);
                GL11.glColor4f((color >> 16 & 255) / 255F, (color >> 8 & 255) / 255F, (color & 255) / 255F, 1);
                NativeCall call = observing == null && developmentDrawGate == null ? null
                    : new NativeCall(++nativeSequence, hand, profile, pass, stack, selectedRenderer, source, bridge);
                invokeNative(renderer, maid, stack, pass, observing, call);
            }
        }
    }

    private static Profile selectProfile(Side side, ItemStack stack, IItemRenderer custom) {
        var shield = MaidShieldItems.inspect(stack);
        if (side == Side.LEFT && shield.status != MaidShieldItems.Status.EMPTY
            && shield.status != MaidShieldItems.Status.UNSUPPORTED) return Profile.SHIELD_LEFT;
        Item item = stack.getItem();
        boolean helper3d = custom != null && custom.shouldUseRenderHelper(
            IItemRenderer.ItemRenderType.EQUIPPED,
            stack,
            IItemRenderer.ItemRendererHelper.BLOCK_3D);
        Block block = item instanceof ItemBlock ? Block.getBlockFromItem(item) : null;
        if (helper3d || block != null && RenderBlocks.renderItemIn3d(block.getRenderType())) return Profile.BLOCK_3D;
        if (item == Items.bow) return Profile.BOW;
        return item.isFull3D() ? Profile.FULL_3D : Profile.FLAT;
    }

    private static void invokeNative(ItemRenderer renderer, EntityLivingBase maid, ItemStack stack, int pass,
        Observer observing, NativeCall call) {
        boolean entered = false, completed = false;
        Throwable failure = null;
        try {
            if (observing != null) observing.beforeNativeCall(call);
            var gate = developmentDrawGate;
            if (gate != null && gate.skipping
                && gate.owner == Thread.currentThread()
                && gate.actor.equals(call.hand.actor)
                && gate.side == call.hand.attachment.side) {
                if (!Boolean.getBoolean("tlmnh.devCommands")) throw new IllegalStateException("Held item gate revoked");
                gate.suppressed++;
                if (observing != null) observing.skippedByDevelopmentGate(call, gate.transaction);
                return;
            }
            entered = true;
            nativeAttempts++;
            renderer.renderItem(maid, stack, pass);
            nativeCompleted++;
            completed = true;
        } catch (RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            if (observing != null) try {
                observing.afterNativeCall(
                    call,
                    entered,
                    completed,
                    failure == null ? ""
                        : failure.getClass()
                            .getName());
            } catch (RuntimeException | Error callback) {
                if (failure == null) throw callback;
                if (failure != callback) failure.addSuppressed(callback);
            }
        }
    }
}
