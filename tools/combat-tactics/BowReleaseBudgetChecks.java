import java.util.Arrays;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota.Work;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatTuning;

/** 使用实际额度和释放入口检查观测与提交的竞争，不启动游戏 */
public final class BowReleaseBudgetChecks {

    public static void main(String[] args) {
        CombatWorkQuota waiting = CombatWorkQuota.isolated(100, false);
        require(waiting.spend(Work.BALLISTIC), "Actual observation charge failed");
        int[] before = waiting.counts();
        require(!MaidBowAttack.hasReleaseBudget(waiting), "Exhausted LIGHT could release a completed draw");
        require(Arrays.equals(before, waiting.counts()), "Waiting preflight charged or refunded work");

        CombatWorkQuota nextFull = CombatWorkQuota.isolated(101, true);
        require(nextFull.spend(Work.BALLISTIC), "Fresh FULL observation charge failed");
        before = nextFull.counts();
        require(MaidBowAttack.hasReleaseBudget(nextFull), "Fresh FULL cannot resume after the same observation");
        require(Arrays.equals(before, nextFull.counts()), "Ready preflight paid the release twice");
        require(
            nextFull.spend(Work.FINAL_CHECK, MaidCombatTuning.BOW_ATTACK_SPEND_SECOND_PARAM)
                && nextFull.spend(Work.RAY, MaidCombatTuning.EQUIPMENT_EPOCH_EQUIPMENT_PARAM)
                && nextFull.spend(Work.BALLISTIC),
            "Unchanged real release footprint could not be paid");
        require(!MaidBowAttack.hasReleaseBudget(nextFull), "Paid shot could release again from the same quota");

        for (Work exhausted : new Work[] { Work.FINAL_CHECK, Work.RAY }) {
            CombatWorkQuota limited = CombatWorkQuota.isolated(102, false);
            int cost = exhausted == Work.FINAL_CHECK ? MaidCombatTuning.BOW_ATTACK_SPEND_SECOND_PARAM
                : MaidCombatTuning.EQUIPMENT_EPOCH_EQUIPMENT_PARAM;
            require(
                limited.spend(exhausted, limited.remaining(exhausted) - cost + 1),
                "Unable to establish one-unit-short actual work");
            require(!MaidBowAttack.hasReleaseBudget(limited), "Final/ray work shortage admitted release: " + exhausted);
            CombatWorkQuota exact = CombatWorkQuota.isolated(102, false);
            require(exact.spend(exhausted, exact.remaining(exhausted) - cost), "Unable to establish exact actual work");
            require(MaidBowAttack.hasReleaseBudget(exact), "Exact existing release cost was unnecessarily refused");
        }
        require(
            MaidBowAttack.hasReleaseBudget(CombatWorkQuota.isolated(103, false)),
            "LIGHT without competing observation was unnecessarily forbidden");
        System.out
            .println("BowReleaseBudgetChecks: actual LIGHT/FULL wait, resume, exact cost and no double payment passed");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
