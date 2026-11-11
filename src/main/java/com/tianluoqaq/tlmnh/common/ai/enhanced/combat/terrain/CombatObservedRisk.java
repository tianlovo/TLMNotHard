package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain;

/** 仅观察子集的同H40结果；有限分量永远不表示隐藏总风险已知 */
public final class CombatObservedRisk {

    private final double[] self, owner;
    public final double singleSelf, singleOwner, hiddenSingle;
    public final boolean complete, acute;

    public CombatObservedRisk(double[] self, double[] owner, double singleSelf, double singleOwner, double hiddenSingle,
        boolean complete, boolean acute) {
        this.self = self.clone();
        this.owner = owner.clone();
        this.singleSelf = singleSelf;
        this.singleOwner = singleOwner;
        this.hiddenSingle = hiddenSingle;
        this.complete = complete;
        this.acute = acute;
    }

    public boolean reserves(CombatSnapshot snapshot) {
        if (!complete || acute || !Double.isFinite(singleSelf + singleOwner + hiddenSingle)) return false;
        for (int h = 0; h < self.length; h++) if (!Double.isFinite(self[h] + owner[h])
            || self[h] + Math.max(singleSelf, hiddenSingle) >= snapshot.self.health
            || snapshot.self.ownerPosition != null
                && owner[h] + Math.max(singleOwner, hiddenSingle) >= snapshot.self.ownerHealth)
            return false;
        return true;
    }

    public boolean nonWorse(CombatObservedRisk other) {
        if (!complete || other == null || !other.complete || self.length != other.self.length) return false;
        for (int h = 0; h < self.length; h++) if (self[h] > other.self[h] || owner[h] > other.owner[h]) return false;
        return true;
    }
}
