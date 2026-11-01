package com.tianluoqaq.tlmnh.common.ai.enhanced;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;

/** 增强返家自行选择步行或有限恢复，不调用原型runner或F日程任务 */
public final class EnhancedHomeController {

    private static final int RECOVERY_RETRY_TICKS = 40;
    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private long nextRecovery, lastTick = Long.MIN_VALUE, attempts, successes;

    public EnhancedHomeController(EntityMaid maid, ModernFollowCoreActor actor) {
        this.maid = maid;
        this.actor = actor;
    }

    private boolean eligible(Point target) {
        var home = maid.getHomeArea();
        var center = home.center();
        return actor.isCurrent() && actor.canBrainMoving()
            && home.enabled()
            && home.sameDimension()
            && !home.containsMaid()
            && target.x == center.x
            && target.y == center.y
            && target.z == center.z;
    }

    public void execute(Point target) {
        if (!eligible(target)) return;
        var home = maid.getHomeArea();
        var settings = home.settings();
        var activity = home.activity();
        int radius = home.radius();
        var feet = actor.scheduleFeet();
        double dx = (double) target.x - feet.x, dy = (double) target.y - feet.y, dz = (double) target.z - feet.z;
        int recoveryDistance = radius + 4;
        long now = actor.worldGameTime();
        if (now < lastTick) nextRecovery = 0;
        lastTick = now;
        if (dx * dx + dy * dy + dz * dz > (double) recoveryDistance * recoveryDistance && now >= nextRecovery) {
            nextRecovery = now + RECOVERY_RETRY_TICKS;
            attempts++;
            double mx = maid.posX, my = maid.posY, mz = maid.posZ;
            float width = maid.width, height = maid.height;
            java.util.function.BooleanSupplier authorized = () -> eligible(target) && home.settings() == settings
                && home.activity() == activity
                && home.radius() == radius
                && maid.posX == mx
                && maid.posY == my
                && maid.posZ == mz
                && maid.width == width
                && maid.height == height;
            if (actor.teleportEnhancedToHome(target, authorized)) {
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
        if (eligible(target)) actor.setPositionWalkAndLook(target, .7F, 1);
    }

    public long attempts() {
        return attempts;
    }

    public long successes() {
        return successes;
    }
}
