package com.tianluoqaq.tlmnh.common.ai.enhanced;

import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorId;
import com.tianluoqaq.tlmnh.api.behavior.TransitionReason;
import com.tianluoqaq.tlmnh.common.ai.enhanced.locomotion.EnhancedNavigationController;
import com.tianluoqaq.tlmnh.common.ai.enhanced.support.MaidTargetCooldowns;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.faithful.follow.FollowMoveActor.BlockPos;
import com.tianluoqaq.tlmnh.common.ai.faithful.perception.MaidModernPerception;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;

/** 拾取先确认完整可达路线，失败只暂避当前实体位置，不改动掉落物或库存 */
public final class EnhancedPickupController {

    public static final MaidBehaviorId ID = MaidBehaviorId.builtin(EnhancedAiIds.ID_PICKUP);

    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final MaidModernPerception perception;
    private final EnhancedNavigationController navigation;
    private final MaidTargetCooldowns<Entity> failed = MaidTargetCooldowns
        .forIdentityClearOnFull(EnhancedAiTuning.MAX_FAILED_TARGETS);
    private Entity target;
    private boolean routeStarted;
    private long rejectedRoutes;

    public EnhancedPickupController(EntityMaid maid, ModernFollowCoreActor actor, MaidModernPerception perception,
        EnhancedNavigationController navigation) {
        this.maid = maid;
        this.actor = actor;
        this.perception = perception;
        this.navigation = navigation;
    }

    public void offer(MaidIntentFrame frame) {
        if (!permitted()) return;
        long now = actor.worldGameTime();
        failed.advanceTick(now);
        failed.purgeExpired(
            now,
            (entity, entry) -> !entity.isEntityAlive() || entity.worldObj != entry.contextRef()
                || !(entry.contextRef() instanceof World world)
                || world.getEntityByID(entity.getEntityId()) != entity
                || entity.getDistanceSq(entry.x(), entry.y(), entry.z())
                    > EnhancedAiTuning.RECONSIDER_DISTANCE_SQUARED);
        if (target != null && routeStarted && failedNavigation()) reject(target, now);
        int inspected = 0;
        for (var candidate : perception.pickupEntities()) {
            if (++inspected > EnhancedAiTuning.MAX_CANDIDATES) break;
            if (!failed.contains(candidate) && legal(candidate)) frame.offerTyped(
                ID,
                Group.AUTONOMOUS,
                Kind.PICKUP,
                candidate.getUniqueID()
                    .toString(),
                EnhancedAiTuning.ENHANCED_AMBIENT_OFFER_UNIT_STEP / (EnhancedAiTuning.ENHANCED_AMBIENT_OFFER_UNIT_STEP
                    + Math.sqrt(maid.getDistanceSqToEntity(candidate))),
                candidate,
                this::execute);
        }
    }

    private boolean failedNavigation() {
        return switch (navigation.outcome()) {
            case UNREACHABLE, PARTIAL_PATH, RECOVERY_WAIT -> true;
            default -> false;
        };
    }

    private boolean permitted() {
        return actor.isCurrent() && maid.isTamed() && maid.isPickup() && actor.canBrainMoving() && !actor.isSleeping();
    }

    private boolean legal(Entity candidate) {
        return permitted() && registered(candidate)
            && maid.canPickup(candidate, true)
            && actor.isCurrent()
            && maid.canEntityBeSeen(candidate)
            && maid.getDistanceSqToEntity(candidate) <= Math.pow(
                maid.getHomeArea()
                    .restrictionRadius() + 1,
                EnhancedAiTuning.ENHANCED_AMBIENT_CLOSER_THAN_SECOND_PARAM)
            && maid.getHomeArea()
                .withinRestriction(
                    MathHelper.floor_double(candidate.posX),
                    MathHelper.floor_double(candidate.posY),
                    MathHelper.floor_double(candidate.posZ))
            && permitted()
            && registered(candidate);
    }

    private boolean registered(Entity candidate) {
        return candidate != null && candidate.worldObj == maid.worldObj
            && candidate.dimension == maid.dimension
            && candidate.isEntityAlive()
            && maid.worldObj.getEntityByID(candidate.getEntityId()) == candidate;
    }

    public void execute(Entity selected) {
        if (!legal(selected) || failed.contains(selected)) return;
        if (target != selected) {
            target = selected;
            routeStarted = false;
        }
        if (!routeStarted) {
            double x = selected.posX, y = selected.posY, z = selected.posZ;
            int entityId = selected.getEntityId();
            var identity = selected.getUniqueID();
            var point = new BlockPos(
                MathHelper.floor_double(x),
                MathHelper.floor_double(y),
                MathHelper.floor_double(z));
            var created = actor.requestEnhancedPath(point);
            if (!actor.isCurrent() || target != selected
                || !legal(selected)
                || selected.getEntityId() != entityId
                || !identity.equals(selected.getUniqueID())
                || selected.posX != x
                || selected.posY != y
                || selected.posZ != z) return;
            switch (created.result) {
                case WAITING_BUDGET, NOT_NAVIGABLE -> {
                    return;
                }
                case UNREACHABLE -> {
                    reject(selected, actor.worldGameTime());
                    return;
                }
                case FOUND -> {
                    if (!created.path.canReach()) {
                        reject(selected, actor.worldGameTime());
                        return;
                    }
                    actor.setEntityWalkAndLook(
                        selected,
                        EnhancedAiTuning.ENHANCED_AMBIENT_SET_WALK_AND_LOOK_TARGET_MEMORIES_S,
                        EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE);
                    navigation.acceptPreflight(point, created, actor.worldGameTime());
                    routeStarted = true;
                    return;
                }
            }
        }
        actor.setEntityWalkAndLook(
            selected,
            EnhancedAiTuning.ENHANCED_AMBIENT_SET_WALK_AND_LOOK_TARGET_MEMORIES_S,
            EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE);
    }

    private void reject(Entity selected, long now) {
        failed.record(
            selected,
            now + EnhancedAiTuning.FAILED_RETRY_TICKS,
            selected.posX,
            selected.posY,
            selected.posZ,
            selected.worldObj);
        rejectedRoutes++;
        target = null;
        routeStarted = false;
    }

    public void onTargetChanged() {
        cancel();
    }

    public void onDeselected(TransitionReason reason) {
        cancel();
    }

    /** 实际路线由根的actor所有权清理；短期失败记录跨正常意图切换保留 */
    public void cancel() {
        target = null;
        routeStarted = false;
    }

    public Entity target() {
        return target;
    }

    public long rejectedRoutes() {
        return rejectedRoutes;
    }
}
