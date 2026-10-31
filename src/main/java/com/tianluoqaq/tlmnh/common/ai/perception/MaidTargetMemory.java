package com.tianluoqaq.tlmnh.common.ai.perception;

import java.util.UUID;

import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;

/** 单目标的最后可见观测，不持久化，也不替代执行时的攻击权限检查 */
public final class MaidTargetMemory {

    public enum Source {
        RC2_ENTITY_SENSES
    }

    private final int maximumAge;
    private Object world;
    private MaidPolicyContext context;
    private MaidPolicyContext.Token token;
    private long frameTime;
    private Observation observation;

    public MaidTargetMemory(int maximumAge) {
        if (maximumAge < 0) throw new IllegalArgumentException("Negative memory age");
        this.maximumAge = maximumAge;
    }

    /** 协调器在策略执行前绑定当前世界、身份租约和世界总 tick */
    public void beginFrame(Object world, MaidPolicyContext context, long gameTime) {
        if (world == null || context == null || !context.isCurrent(context.getToken())) {
            clear();
            return;
        }
        if (this.world != world || this.context != context || token != context.getToken() || gameTime < frameTime)
            clear();
        this.world = world;
        this.context = context;
        token = context.getToken();
        frameTime = gameTime;
    }

    /** 仅由原生 EntitySenses 确认可见的目标写入；隐藏目标的位置不得更新此槽 */
    public boolean observeVisible(UUID targetId, Object target, double x, double footY, double z, double eyeY) {
        if (!current() || targetId == null
            || target == null
            || !Double.isFinite(x)
            || !Double.isFinite(footY)
            || !Double.isFinite(z)
            || !Double.isFinite(eyeY)) {
            observation = null;
            return false;
        }
        observation = new Observation(targetId, target, frameTime, x, footY, z, eyeY);
        return true;
    }

    public Observation recall(UUID targetId, Object target) {
        if (!current()) {
            observation = null;
            return null;
        }
        if (observation == null) return null;
        long age = frameTime - observation.observedAt;
        if (age < 0 || age > maximumAge) {
            observation = null;
            return null;
        }
        return observation.target == target && observation.targetId.equals(targetId) ? observation : null;
    }

    public void forgetTarget() {
        observation = null;
    }

    public void clear() {
        observation = null;
        world = null;
        context = null;
        token = null;
        frameTime = 0;
    }

    private boolean current() {
        return world != null && context != null && context.isCurrent(token);
    }

    public static final class Observation {

        private final UUID targetId;
        private final transient Object target;
        private final long observedAt;
        private final double x, footY, z, eyeY;

        private Observation(UUID targetId, Object target, long observedAt, double x, double footY, double z,
            double eyeY) {
            this.targetId = targetId;
            this.target = target;
            this.observedAt = observedAt;
            this.x = x;
            this.footY = footY;
            this.z = z;
            this.eyeY = eyeY;
        }

        public UUID getTargetId() {
            return targetId;
        }

        public long getObservedAt() {
            return observedAt;
        }

        public Source getSource() {
            return Source.RC2_ENTITY_SENSES;
        }

        public double getX() {
            return x;
        }

        public double getFootY() {
            return footY;
        }

        public double getZ() {
            return z;
        }

        public double getEyeY() {
            return eyeY;
        }
    }
}
