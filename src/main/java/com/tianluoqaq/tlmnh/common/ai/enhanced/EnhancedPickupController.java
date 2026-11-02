package com.tianluoqaq.tlmnh.common.ai.enhanced;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.BlockPos;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidModernPerception;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;

/** 拾取先确认完整可达路线，失败只暂避当前实体位置，不改动掉落物或库存 */
public final class EnhancedPickupController {

    private static final int MAX_CANDIDATES = 64, MAX_FAILED_TARGETS = 32, FAILED_RETRY_TICKS = 100;
    private static final double RECONSIDER_DISTANCE_SQUARED = 1;
    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final MaidModernPerception perception;
    private final EnhancedNavigationController navigation;
    private final Map<Entity, FailedApproach> failed = new IdentityHashMap<>();
    private Entity target;
    private boolean routeStarted;
    private long lastTick = Long.MIN_VALUE, rejectedRoutes;

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
        if (now < lastTick) failed.clear();
        lastTick = now;
        failed.entrySet()
            .removeIf(
                entry -> entry.getValue()
                    .expired(entry.getKey(), now));
        if (target != null && routeStarted && failedNavigation()) reject(target, now);
        int inspected = 0;
        for (var candidate : perception.pickupEntities()) {
            if (++inspected > MAX_CANDIDATES) break;
            if (!failed.containsKey(candidate) && legal(candidate)) frame.offer(
                Group.AUTONOMOUS,
                Kind.PICKUP,
                candidate.getUniqueID()
                    .toString(),
                1 / (1 + Math.sqrt(maid.getDistanceSqToEntity(candidate))),
                candidate);
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
                2)
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
        if (!legal(selected) || failed.containsKey(selected)) return;
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
                    actor.setEntityWalkAndLook(selected, .6F, 0);
                    navigation.acceptPreflight(point, created, actor.worldGameTime());
                    routeStarted = true;
                    return;
                }
            }
        }
        actor.setEntityWalkAndLook(selected, .6F, 0);
    }

    private void reject(Entity selected, long now) {
        if (failed.size() >= MAX_FAILED_TARGETS) failed.clear();
        failed.put(selected, new FailedApproach(selected, now + FAILED_RETRY_TICKS));
        rejectedRoutes++;
        target = null;
        routeStarted = false;
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

    private static final class FailedApproach {

        final net.minecraft.world.World world;
        final double x, y, z;
        final long retryAt;

        FailedApproach(Entity entity, long retryAt) {
            world = entity.worldObj;
            x = entity.posX;
            y = entity.posY;
            z = entity.posZ;
            this.retryAt = retryAt;
        }

        boolean expired(Entity entity, long now) {
            return now >= retryAt || !entity.isEntityAlive()
                || entity.worldObj != world
                || world.getEntityByID(entity.getEntityId()) != entity
                || entity.getDistanceSq(x, y, z) > RECONSIDER_DISTANCE_SQUARED;
        }
    }
}
