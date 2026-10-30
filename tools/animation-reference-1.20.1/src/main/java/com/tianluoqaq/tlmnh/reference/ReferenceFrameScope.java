package com.tianluoqaq.tlmnh.reference;

import java.util.Random;

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

    private ReferenceFrameScope(EntityMaid maid, float partialTick, boolean render) {
        this.maid = maid;
        this.partialTick = partialTick;
        this.render = render;
        previousTick = maid == null ? 0 : maid.tickCount;
        previousGround = maid != null && maid.onGround();
        rotations = maid == null ? null : new float[] {maid.getYRot(), maid.yRotO, maid.getXRot(), maid.xRotO,
            maid.yBodyRot, maid.yBodyRotO, maid.yHeadRot, maid.yHeadRotO};
    }

    static ReferenceFrameScope uncontrolled(float partialTick) {
        return new ReferenceFrameScope(null, partialTick, true);
    }

    static ReferenceFrameScope skip(float partialTick) {
        return new ReferenceFrameScope(null, partialTick, false);
    }

    static ReferenceFrameScope controlled(EntityMaid maid, int tick, long seed, boolean resetRandom) {
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
        var scope = new ReferenceFrameScope(maid, 0, true);
        maid.tickCount = tick;
        maid.setOnGround(true);
        maid.setYRot(180);
        maid.yRotO = maid.yBodyRot = maid.yBodyRotO = maid.yHeadRot = maid.yHeadRotO = 180;
        maid.setXRot(0);
        maid.xRotO = 0;
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
    }
}
