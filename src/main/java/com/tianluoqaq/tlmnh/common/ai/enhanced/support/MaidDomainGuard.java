package com.tianluoqaq.tlmnh.common.ai.enhanced.support;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;

/** 统一女仆家园与主人活动域判定；家园启用时按配置域裁决，非家园按主人半径裁决 */
public final class MaidDomainGuard {

    private MaidDomainGuard() {}

    public static boolean isMaidWithinDomain(EntityMaid maid, ModernFollowCoreActor actor, double ownerRadius) {
        var home = maid.getHomeArea();
        if (home.enabled()) return home.sameDimension() && home.containsMaid();
        var owner = actor.getOwner();
        return owner != null && !owner.isDeadOrDying()
            && !owner.isSpectator()
            && owner.getWorldIdentity() == actor.getWorldIdentity()
            && actor.closerThan(owner, ownerRadius);
    }

    public static boolean isPointWithinActiveDomain(EntityMaid maid, double x, double y, double z) {
        return isPointWithinDomain(maid, x, y, z, EnhancedSupportTuning.DEFAULT_OWNER_DOMAIN_RADIUS);
    }

    public static boolean isPointWithinDomain(EntityMaid maid, double x, double y, double z, double ownerRadius) {
        var home = maid.getHomeArea();
        if (home.enabled()) {
            return home.sameDimension() && home
                .withinRestriction(MathHelper.floor_double(x), MathHelper.floor_double(y), MathHelper.floor_double(z));
        }
        var owner = maid.getOwner();
        if (owner == null || owner.worldObj != maid.worldObj
            || owner.dimension != maid.dimension
            || !owner.isEntityAlive()
            || owner instanceof EntityPlayerMP mp && mp.theItemInWorldManager.getGameType()
                .getID() == EnhancedSupportTuning.TARGET_GET_ID_THRESHOLD)
            return false;
        return owner.getDistanceSq(x, y, z) <= ownerRadius * ownerRadius;
    }
}
