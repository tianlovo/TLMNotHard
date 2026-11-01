package com.tianluoqaq.tlmnh.common.ai.enhanced;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowOwnerActor.Owner;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;

/** 正常跟随继续走路，达到原版掉队范围才尝试有限安全落点；失败退回导航并限频重试 */
public final class EnhancedFollowController {

    private static final int RECOVERY_RETRY_TICKS = 20;
    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private long nextRecovery, lastTick = Long.MIN_VALUE, attempts, successes;

    public EnhancedFollowController(EntityMaid maid, ModernFollowCoreActor actor) {
        this.maid = maid;
        this.actor = actor;
    }

    private boolean eligible(Owner owner) {
        return actor.isCurrent() && owner != null
            && !owner.isSpectator()
            && !owner.isDeadOrDying()
            && owner.getWorldIdentity() == actor.getWorldIdentity()
            && actor.canBrainMoving()
            && !maid.getHomeArea()
                .enabled()
            && actor.ownerMatchesEntity(owner, maid.getOwner());
    }

    public void execute(Owner owner) {
        if (!eligible(owner)) return;
        long now = actor.worldGameTime();
        if (now < lastTick) nextRecovery = 0;
        lastTick = now;
        int recoveryDistance = (int) actor.getRestrictRadius() + 2;
        if (!actor.closerThan(owner, recoveryDistance) && now >= nextRecovery) {
            nextRecovery = now + RECOVERY_RETRY_TICKS;
            attempts++;
            var player = maid.getOwner();
            double px = player.posX, py = player.posY, pz = player.posZ;
            double mx = maid.posX, my = maid.posY, mz = maid.posZ;
            float width = maid.width, height = maid.height;
            java.util.function.BooleanSupplier authorized = () -> eligible(owner) && maid.getOwner() == player
                && player.posX == px
                && player.posY == py
                && player.posZ == pz
                && maid.posX == mx
                && maid.posY == my
                && maid.posZ == mz
                && maid.width == width
                && maid.height == height;
            if (actor.teleportEnhancedToOwner(owner, authorized)) {
                successes++;
                return;
            }
            if (!authorized.getAsBoolean()) {
                if (actor.isCurrent()) {
                    actor.eraseWalkTarget();
                    actor.stopNavigation();
                }
                return;
            }
        }
        if (eligible(owner)) actor.setWalkAndLookTargetMemories(owner, .5F, 2);
    }

    public long attempts() {
        return attempts;
    }

    public long successes() {
        return successes;
    }
}
