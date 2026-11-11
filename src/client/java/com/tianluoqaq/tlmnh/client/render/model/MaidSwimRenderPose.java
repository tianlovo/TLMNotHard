/*
 * Bedrock swim transforms adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/animation/special/SwimAnimation.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: per-entity client tick interpolation on Forge 1.7.10
 */
package com.tianluoqaq.tlmnh.client.render.model;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.world.WorldEvent;

import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.entity.MaidEntityPose;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 原SwimAnimation的Bedrock整体变换；每实体自然客户端tick推进一次，绘制和预览只读插值 */
@SideOnly(Side.CLIENT)
public final class MaidSwimRenderPose {

    private static final MaidSwimRenderPose INSTANCE = new MaidSwimRenderPose();
    private static final Map<EntityMaid, Transition> TRANSITIONS = new WeakHashMap<>();
    private static boolean registered;

    private MaidSwimRenderPose() {}

    public static void register() {
        if (registered) return;
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        registered = true;
    }

    @SubscribeEvent
    public void living(LivingEvent.LivingUpdateEvent event) {
        if (!(event.entityLiving instanceof EntityMaid maid) || !maid.worldObj.isRemote) return;
        var transition = TRANSITIONS.computeIfAbsent(maid, ignored -> new Transition());
        if (transition.tick == maid.ticksExisted) return;
        if (transition.tick > maid.ticksExisted)
            transition.previous = transition.current = BuiltinModelLayout.MAX_INITIAL_VALUE;
        transition.tick = maid.ticksExisted;
        transition.previous = transition.current;
        // MC1.20.1 LivingEntity.updateSwimAmount，以真实视觉Pose而非游泳意图位为准
        transition.current = maid.getEntityPose() == MaidEntityPose.SWIMMING
            ? Math.min(
                BuiltinModelLayout.MIN_SINGLE_UNIT,
                transition.current + BuiltinModelLayout.SWIM_RENDER_MIN_SECOND_FACTOR)
            : Math.max(
                BuiltinModelLayout.MAX_INITIAL_VALUE,
                transition.current - BuiltinModelLayout.SWIM_RENDER_MIN_SECOND_FACTOR);
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        if (event.world.isRemote) TRANSITIONS.keySet()
            .removeIf(maid -> maid.worldObj == event.world);
    }

    public static float amount(EntityMaid maid, float partialTick) {
        var transition = TRANSITIONS.get(maid);
        if (transition == null) return BuiltinModelLayout.MAX_INITIAL_VALUE;
        float partial = Math
            .max(BuiltinModelLayout.MAX_INITIAL_VALUE, Math.min(BuiltinModelLayout.MIN_SINGLE_UNIT, partialTick));
        return transition.previous + (transition.current - transition.previous) * partial;
    }

    /** 在父级旋转及背负变换之后调用，本体与后续装备共用同一矩阵 */
    public static void apply(EntityMaid maid, BuiltinMaidModel model, float partialTick) {
        // 固定原SwimAnimation没有覆写setupGeckoRotations，Gecko继续使用自身swim动画
        if (model.getGecko() != null) return;
        float angle = -(BuiltinModelLayout.SWIM_RENDER_APPLY_PARAM
            + (maid.isInWater() ? maid.rotationPitch : BuiltinModelLayout.MAX_INITIAL_VALUE))
            * amount(maid, partialTick);
        GL11.glRotatef(
            angle,
            BuiltinModelLayout.MIN_SINGLE_UNIT,
            BuiltinModelLayout.MAX_INITIAL_VALUE,
            BuiltinModelLayout.MAX_INITIAL_VALUE);
        if (maid.getEntityPose() == MaidEntityPose.SWIMMING) GL11.glTranslatef(
            BuiltinModelLayout.MAX_INITIAL_VALUE,
            BuiltinModelLayout.UNSET_OR_NOT_FOUND,
            BuiltinModelLayout.SWIM_RENDER_APPLY_OFFSET_Z);
    }

    private static final class Transition {

        private int tick = Integer.MIN_VALUE;
        private float previous, current;
    }
}
