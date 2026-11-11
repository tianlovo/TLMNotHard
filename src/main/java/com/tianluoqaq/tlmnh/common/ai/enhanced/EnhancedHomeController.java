package com.tianluoqaq.tlmnh.common.ai.enhanced;

import java.util.function.BooleanSupplier;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorId;
import com.tianluoqaq.tlmnh.common.ai.enhanced.locomotion.EnhancedRideController;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;

/** 增强返家自行选择步行或有限恢复，不调用原型runner或F日程任务 */
public final class EnhancedHomeController {

    public static final MaidBehaviorId ID = MaidBehaviorId.builtin(EnhancedAiIds.ID_HOME_RETURN);

    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private long nextRecovery, lastTick = Long.MIN_VALUE, attempts, successes;

    public EnhancedHomeController(EntityMaid maid, ModernFollowCoreActor actor) {
        this.maid = maid;
        this.actor = actor;
    }

    public void offer(MaidIntentFrame frame, boolean vehicleOffered) {
        var home = maid.getHomeArea();
        if (!EnhancedRideController.stationary(maid) && !vehicleOffered && home.enabled() && !home.containsMaid()) {
            var center = home.center();
            var target = new Point(center.x, center.y, center.z);
            frame.offerTyped(
                ID,
                Group.COMMAND,
                Kind.HOME,
                EnhancedAiIds.ID_HOME + home.settings().dimension + EnhancedAiIds.COLON_DELIMITER + center,
                EnhancedAiTuning.ENHANCED_AMBIENT_OFFER_UNIT_STEP,
                target,
                this::execute);
        }
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
        int recoveryDistance = radius + EnhancedAiTuning.ENHANCED_HOME_RECOVERY_DISTANCE;
        long now = actor.worldGameTime();
        if (now < lastTick) nextRecovery = EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE;
        lastTick = now;
        if (dx * dx + dy * dy + dz * dz > (double) recoveryDistance * recoveryDistance && now >= nextRecovery) {
            nextRecovery = now + EnhancedAiTuning.RECOVERY_RETRY_TICKS;
            attempts++;
            double mx = maid.posX, my = maid.posY, mz = maid.posZ;
            float width = maid.width, height = maid.height;
            BooleanSupplier authorized = () -> eligible(target) && home.settings() == settings
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
        if (eligible(target)) actor.setPositionWalkAndLook(
            target,
            EnhancedAiTuning.ENHANCED_HOME_SET_POSITION_WALK_AND_LOOK_SECOND_FACT,
            EnhancedAiTuning.ENHANCED_AMBIENT_OFFER_UNIT_STEP);
    }

    public long attempts() {
        return attempts;
    }

    public long successes() {
        return successes;
    }
}
