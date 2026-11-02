package com.tianluoqaq.tlmnh.common.combat.shield;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 只由原生箭碰撞的单次伤害调用持有，直接伤害调用没有该提交权 */
public interface MaidShieldArrowImpact {

    Object tlmnh$shieldImpactCall(EntityMaid target);

    boolean tlmnh$isShieldImpactCurrent(EntityMaid target, Object call);

    boolean tlmnh$recordShieldCapture(EntityMaid target, Object call);
}
