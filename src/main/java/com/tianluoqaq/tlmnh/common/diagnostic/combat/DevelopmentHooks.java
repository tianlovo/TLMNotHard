package com.tianluoqaq.tlmnh.common.diagnostic.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlan;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlanner;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot;

/** 服务端与公共业务观察点契约，生产运行期默认挂载无操作单例且不分配临时对象 */
public interface DevelopmentHooks {

    DevelopmentHooks NONE = new DevelopmentHooks() {};

    enum CombatPhase {
        OBSERVATION,
        OFFER,
        SNAPSHOT,
        SUBMIT
    }

    @FunctionalInterface
    interface SafeCloseable extends AutoCloseable {

        SafeCloseable NOOP = () -> {};

        @Override
        void close();
    }

    interface CombatTrialHook {

        CombatPlan decide(CombatSnapshot snapshot, CombatWorkQuota quota, CombatPlanner planner);

        void applied(CombatPlan.Result result, CombatWorkQuota quota);

        void accountObservation(CombatWorkQuota quota);
    }

    default SafeCloseable measureCombat(EntityMaid maid, CombatPhase phase) {
        return SafeCloseable.NOOP;
    }

    default CombatTrialHook currentCombatTrial(EntityMaid maid) {
        return null;
    }
}
