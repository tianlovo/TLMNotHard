package com.tianluoqaq.tlmnh.common.ai.navigation;

import java.util.Objects;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatQueuedMove;

/** 原导航最终坐标已算出后的一次窄适配结果，REJECT不得回落普通下发 */
public final class MaidCombatMoveDispatch {

    public enum Kind {
        DEFAULT,
        REJECT,
        OWNED
    }

    public static final MaidCombatMoveDispatch DEFAULT = new MaidCombatMoveDispatch(Kind.DEFAULT, null);
    public static final MaidCombatMoveDispatch REJECT = new MaidCombatMoveDispatch(Kind.REJECT, null);
    public final Kind kind;
    public final CombatQueuedMove command;

    private MaidCombatMoveDispatch(Kind kind, CombatQueuedMove command) {
        this.kind = kind;
        this.command = command;
    }

    public static MaidCombatMoveDispatch owned(CombatQueuedMove command) {
        return new MaidCombatMoveDispatch(Kind.OWNED, Objects.requireNonNull(command));
    }
}
