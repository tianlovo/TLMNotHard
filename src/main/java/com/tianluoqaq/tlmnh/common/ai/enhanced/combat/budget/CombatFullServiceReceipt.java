package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget;

/** 一次真实FULL服务的纯值回执；与是否完成战斗预演分别结算 */
public final class CombatFullServiceReceipt {

    public enum Status {
        NOT_GRANTED,
        GRANTED_NOT_CAPTURED,
        BODY_CAPTURE_ATTEMPTED_INCOMPLETE,
        BODY_READY,
        COMBAT_FORECAST_COMPLETE
    }

    private long tick = Long.MIN_VALUE, settledAt = Long.MIN_VALUE;
    private Status status = Status.NOT_GRANTED;

    public void granted(long now) {
        if (tick != now) {
            tick = now;
            status = Status.GRANTED_NOT_CAPTURED;
        }
    }

    public boolean beginCapture(long now) {
        granted(now);
        if (status != Status.GRANTED_NOT_CAPTURED) return false;
        status = Status.BODY_CAPTURE_ATTEMPTED_INCOMPLETE;
        return true;
    }

    public void captured(long now, boolean body, boolean combat) {
        if (tick != now || status == Status.GRANTED_NOT_CAPTURED || status == Status.NOT_GRANTED)
            throw new IllegalStateException(CombatBudgetDiagnostics.MSG_NO_REAL_CAPTURE_ATTEMPT);
        status = combat || status == Status.COMBAT_FORECAST_COMPLETE ? Status.COMBAT_FORECAST_COMPLETE
            : body ? Status.BODY_READY : Status.BODY_CAPTURE_ATTEMPTED_INCOMPLETE;
    }

    public Status status(long now) {
        return tick == now ? status : Status.NOT_GRANTED;
    }

    public boolean attempted(long now) {
        return tick == now && status != Status.NOT_GRANTED && status != Status.GRANTED_NOT_CAPTURED;
    }

    public boolean settle(long now) {
        if (!attempted(now) || settledAt == now) return false;
        settledAt = now;
        return true;
    }

    public void clear() {
        tick = settledAt = Long.MIN_VALUE;
        status = Status.NOT_GRANTED;
    }
}
