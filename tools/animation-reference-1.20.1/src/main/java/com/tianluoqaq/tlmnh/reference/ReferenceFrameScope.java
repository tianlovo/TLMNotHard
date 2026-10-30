package com.tianluoqaq.tlmnh.reference;

import java.util.Random;

import net.minecraft.world.entity.WalkAnimationState;
import net.minecraft.world.phys.Vec3;

import com.github.tartaricacid.touhoulittlemaid.capability.GeckoMaidEntityCapabilityProvider;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.processor.AnimationProcessor;

/** 只对当前实验实体临时提供固定输入，离开绘制调用即恢复实体字段 */
public final class ReferenceFrameScope implements AutoCloseable {
    private final EntityMaid maid;
    private final float partialTick;
    private final boolean render;
    private final int previousTick;
    private final boolean previousGround;
    private final float[] rotations;
    private final Kinematics previousKinematics;

    private ReferenceFrameScope(EntityMaid maid, float partialTick, boolean render) {
        this(maid, partialTick, render, false);
    }

    private ReferenceFrameScope(EntityMaid maid, float partialTick, boolean render, boolean kinematic) {
        this.maid = maid;
        this.partialTick = partialTick;
        this.render = render;
        previousTick = maid == null ? 0 : maid.tickCount;
        previousGround = maid != null && maid.onGround();
        rotations = maid == null ? null : new float[] {maid.getYRot(), maid.yRotO, maid.getXRot(), maid.xRotO,
            maid.yBodyRot, maid.yBodyRotO, maid.yHeadRot, maid.yHeadRotO};
        previousKinematics = kinematic ? new Kinematics(maid) : null;
    }

    static ReferenceFrameScope uncontrolled(float partialTick) {
        return new ReferenceFrameScope(null, partialTick, true);
    }

    static ReferenceFrameScope skip(float partialTick) {
        return new ReferenceFrameScope(null, partialTick, false);
    }

    static ReferenceFrameScope controlled(EntityMaid maid, int tick, long seed, boolean resetRandom, String scenario) {
        if (resetRandom) {
            maid.getRandom().setSeed(seed);
            var animatable = maid.getCapability(GeckoMaidEntityCapabilityProvider.CAP).orElse(null);
            if (animatable == null) throw new IllegalStateException("Original Gecko capability is missing");
            try {
                var field = AnimationProcessor.class.getDeclaredField("random");
                field.setAccessible(true);
                ((Random) field.get(animatable.getAnimationProcessor())).setSeed(seed);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Original animation RNG could not be initialized", error);
            }
        }
        boolean walking = ReferenceScenario.IDLE_WALK_IDLE.equals(scenario);
        boolean kinematic = ReferenceScenario.sequenced(scenario);
        var scope = new ReferenceFrameScope(maid, 0, true, kinematic);
        maid.tickCount = tick;
        maid.setOnGround(true);
        maid.setYRot(180);
        maid.yRotO = maid.yBodyRot = maid.yBodyRotO = maid.yHeadRot = maid.yHeadRotO = 180;
        maid.yHeadRot = maid.yHeadRotO = 180 + ReferenceScenario.headYaw(scenario, tick);
        maid.setXRot(ReferenceScenario.headPitch(scenario, tick));
        maid.xRotO = maid.getXRot();
        if (kinematic) {
            double movement = walking ? ReferenceScenario.motionX(tick) : 0;
            maid.setDeltaMovement(movement, 0, 0);
            maid.xo = maid.getX() - movement;
            maid.yo = maid.getY();
            maid.zo = maid.getZ();
            float amount = walking ? ReferenceScenario.amount(tick) : 0;
            float limb = walking ? ReferenceScenario.limb(tick) : 0;
            setWalk(maid.walkAnimation, amount, amount, limb + amount);
        }
        return scope;
    }

    public boolean shouldRender() {
        return render;
    }

    public float partialTick() {
        return partialTick;
    }

    @Override
    public void close() {
        if (maid == null) return;
        maid.tickCount = previousTick;
        maid.setOnGround(previousGround);
        maid.setYRot(rotations[0]);
        maid.yRotO = rotations[1];
        maid.setXRot(rotations[2]);
        maid.xRotO = rotations[3];
        maid.yBodyRot = rotations[4];
        maid.yBodyRotO = rotations[5];
        maid.yHeadRot = rotations[6];
        maid.yHeadRotO = rotations[7];
        if (previousKinematics != null) previousKinematics.restore(maid);
    }

    /** 通过原版公开API设置三个值，避免依赖Minecraft运行时私有字段名称 */
    private static void setWalk(WalkAnimationState walk, float oldSpeed, float speed, float position) {
        walk.setSpeed(0);
        walk.update(position - speed - walk.position(), 1);
        walk.setSpeed(oldSpeed);
        walk.update(speed, 1);
    }

    private static final class Kinematics {
        private final Vec3 movement;
        private final double xo, yo, zo;
        private final float oldSpeed, speed, position;

        private Kinematics(EntityMaid maid) {
            movement = maid.getDeltaMovement();
            xo = maid.xo;
            yo = maid.yo;
            zo = maid.zo;
            oldSpeed = maid.walkAnimation.speed(0);
            speed = maid.walkAnimation.speed();
            position = maid.walkAnimation.position();
        }

        private void restore(EntityMaid maid) {
            maid.setDeltaMovement(movement);
            maid.xo = xo;
            maid.yo = yo;
            maid.zo = zo;
            setWalk(maid.walkAnimation, oldSpeed, speed, position);
        }
    }
}
