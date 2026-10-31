package com.tianluoqaq.tlmnh.common.ai.faithful;

/**
 * 按固定MC1.20.1的LookAtTargetSink和Behavior字节码适配的独立组件
 * 仅执行记忆、持续时间和look请求；不tick原生helper，不代表完整Brain或感知缓存
 * 原始MC/Forge方法仅作为行为核对依据，原创适配见SOURCE_PROVENANCE.md
 */
public final class FollowCoreLookDriver {

    public enum Status {
        STOPPED,
        RUNNING
    }

    public interface Actor {

        boolean isLookTargetRegistered();

        boolean hasLookTarget();

        boolean isCurrentLookTargetVisible();

        void lookAtCurrentTargetIfPresent();

        void eraseLookTarget();

        int nextWorldInt(int bound);
    }

    public static final class State {

        private Status status = Status.STOPPED;
        private long endTimestamp;

        public Status getStatus() {
            return status;
        }

        public long getEndTimestamp() {
            return endTimestamp;
        }
    }

    private final int minDuration, maxDuration;

    public FollowCoreLookDriver() {
        this(45, 90);
    }

    public FollowCoreLookDriver(int minDuration, int maxDuration) {
        if (minDuration < 0 || maxDuration < minDuration || (long) maxDuration - minDuration + 1 > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid look behavior duration range");
        this.minDuration = minDuration;
        this.maxDuration = maxDuration;
    }

    /** 开始pass仅对STOPPED行为调用；visibility属于运行条件，不提前拦截duration RNG */
    public boolean tryStart(Actor actor, State state, long behaviorTime) {
        if (!actor.isLookTargetRegistered() || !actor.hasLookTarget()) return false;
        state.status = Status.RUNNING;
        int bound = maxDuration + 1 - minDuration;
        int random = actor.nextWorldInt(bound);
        if (random < 0 || random >= bound) throw new IllegalArgumentException("World integer outside requested bound");
        state.endTimestamp = behaviorTime + minDuration + random;
        return true;
    }

    /** 运行pass读取当前LOOK，不能在tryStart时冻结目标或可见性 */
    public void tickOrStop(Actor actor, State state, long behaviorTime) {
        if (behaviorTime > state.endTimestamp || !actor.isCurrentLookTargetVisible()) {
            doStop(actor, state, behaviorTime);
        } else {
            actor.lookAtCurrentTargetIfPresent();
        }
    }

    /** 原stop只擦LOOK，不清helper中已经提交的请求，也不回写角度 */
    public void doStop(Actor actor, State state, long behaviorTime) {
        state.status = Status.STOPPED;
        actor.eraseLookTarget();
    }
}
