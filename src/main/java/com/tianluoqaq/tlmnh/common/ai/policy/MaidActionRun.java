package com.tianluoqaq.tlmnh.common.ai.policy;

import java.util.Objects;

/** 单次动作身份与一次性提交门槛，资源清理由持有该实例的执行器负责 */
public final class MaidActionRun {

    private enum Phase {
        ACTIVE,
        COMMITTING,
        COMMITTED,
        FAILED,
        CANCELLED
    }

    private final MaidPolicyContext context;
    private final MaidPolicyContext.Token token;
    private final Object world, command;
    private Phase phase = Phase.ACTIVE;

    public MaidActionRun(MaidPolicyContext context, MaidPolicyContext.Token token, Object world, Object command) {
        this.context = Objects.requireNonNull(context, "context");
        this.token = Objects.requireNonNull(token, "token");
        this.world = Objects.requireNonNull(world, "world");
        this.command = Objects.requireNonNull(command, "command");
    }

    public boolean matches(MaidPolicyContext context, MaidPolicyContext.Token token) {
        return this.context == context && this.token == token;
    }

    public boolean isCurrent(Object world, Object command) {
        return this.world == world && this.command == command
            && context.isCurrent(token)
            && phase != Phase.FAILED
            && phase != Phase.CANCELLED;
    }

    public boolean tryCommit(Object world, Object command) {
        if (phase != Phase.ACTIVE || !isCurrent(world, command)) return false;
        phase = Phase.COMMITTING;
        return true;
    }

    public boolean finishCommit(boolean effectConfirmed) {
        if (!context.isCurrent(token)) phase = Phase.CANCELLED;
        if (phase != Phase.COMMITTING) return false;
        phase = effectConfirmed ? Phase.COMMITTED : Phase.FAILED;
        return effectConfirmed;
    }

    public boolean isCancelled() {
        return phase == Phase.CANCELLED || !context.isCurrent(token);
    }

    public void cancel() {
        phase = Phase.CANCELLED;
    }
}
