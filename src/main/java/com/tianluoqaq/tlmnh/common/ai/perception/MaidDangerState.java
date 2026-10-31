package com.tianluoqaq.tlmnh.common.ai.perception;

import java.util.UUID;

import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;

/** 危险观测与扫描续点；截断不是阴性证据，也不能续期旧事实 */
public final class MaidDangerState {

    public static final int SCAN_BUDGET = 32;
    public static final int MAXIMUM_AGE = 20;

    public enum Result {
        VISIBLE_HOSTILE,
        CLEAR,
        UNKNOWN
    }

    public enum Source {
        RC2_ENTITY_SENSES,
        COMPLETE_NATIVE_AABB_SCAN
    }

    private Object world;
    private MaidPolicyContext context;
    private MaidPolicyContext.Token token;
    private long frameTime;
    private int cursor;
    private Observation observation;

    /** 返回是否更换了观测范围，供执行端同时重置扫描冷却 */
    public boolean beginFrame(Object world, MaidPolicyContext context, long gameTime) {
        boolean changed = this.world != world || this.context != context
            || context == null
            || token != context.getToken()
            || gameTime < frameTime;
        if (changed) clear();
        if (world == null || context == null || !context.isCurrent(context.getToken())) {
            clear();
            return true;
        }
        this.world = world;
        this.context = context;
        token = context.getToken();
        frameTime = gameTime;
        return changed;
    }

    public boolean isFrame(Object world, long gameTime) {
        return world != null && this.world == world
            && frameTime == gameTime
            && context != null
            && context.isCurrent(token);
    }

    public int scanStart(int population) {
        return population == 0 ? 0 : Math.floorMod(cursor, population);
    }

    public void recordScan(int population, int inspected, UUID targetId, Object target) {
        if (population < 0 || inspected < 0
            || inspected > population
            || inspected > SCAN_BUDGET
            || (target == null) != (targetId == null)) throw new IllegalArgumentException("Invalid danger scan");
        if (!isFrame(world, frameTime)) return;
        cursor = population == 0 ? 0 : (int) (((long) scanStart(population) + inspected) % population);
        if (target != null) observation = new Observation(Result.VISIBLE_HOSTILE, frameTime, targetId, target);
        else if (population == inspected) observation = new Observation(Result.CLEAR, frameTime, null, null);
    }

    public Observation getObservation() {
        if (!isFrame(world, frameTime)) observation = null;
        if (observation != null) {
            long age = frameTime - observation.observedAt;
            if (age < 0 || age > MAXIMUM_AGE) observation = null;
        }
        return observation;
    }

    public Result getResult() {
        var current = getObservation();
        return current == null ? Result.UNKNOWN : current.result;
    }

    /** 死亡、卸载或引用失配只删除阳性事实，不能推导周围已清空 */
    public void discardTarget() {
        if (observation != null && observation.result == Result.VISIBLE_HOSTILE) observation = null;
    }

    public void clear() {
        world = null;
        context = null;
        token = null;
        observation = null;
        cursor = 0;
        frameTime = 0;
    }

    public static final class Observation {

        private final Result result;
        private final long observedAt;
        private final UUID targetId;
        private final transient Object target;

        private Observation(Result result, long observedAt, UUID targetId, Object target) {
            this.result = result;
            this.observedAt = observedAt;
            this.targetId = targetId;
            this.target = target;
        }

        public long getObservedAt() {
            return observedAt;
        }

        public Source getSource() {
            return result == Result.VISIBLE_HOSTILE ? Source.RC2_ENTITY_SENSES : Source.COMPLETE_NATIVE_AABB_SCAN;
        }

        public UUID getTargetId() {
            return targetId;
        }

        public Object getTarget() {
            return target;
        }
    }
}
