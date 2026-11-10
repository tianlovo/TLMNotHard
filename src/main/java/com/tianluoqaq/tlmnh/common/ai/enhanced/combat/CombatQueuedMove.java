package com.tianluoqaq.tlmnh.common.ai.enhanced.combat;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Actor辅助租约的一条真实下发输入；引用身份用于撤销，消费不取得新的地形或行动许可 */
public final class CombatQueuedMove {

    private final BooleanSupplier current;
    private final Consumer<CombatQueuedMove> rejected;
    private boolean consumed;

    public CombatQueuedMove(BooleanSupplier current, Consumer<CombatQueuedMove> rejected) {
        this.current = Objects.requireNonNull(current);
        this.rejected = Objects.requireNonNull(rejected);
    }

    public boolean current() {
        return !consumed && current.getAsBoolean();
    }

    public boolean consume() {
        if (!current()) return false;
        consumed = true;
        return true;
    }

    public void rejected() {
        rejected.accept(this);
    }
}
