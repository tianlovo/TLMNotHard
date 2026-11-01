package com.tianluoqaq.tlmnh.common.ai.enhanced;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityOcelot;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowOwnerActor.Owner;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidModernPerception;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidSensingBudget;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidSensingSession;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Intent;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** 无其他活动时才选择短程闲逛或观察；请求食物只表现意图，不取走主人手中物品 */
public final class EnhancedAmbientController {

    private static final int CHOICE_INTERVAL = 20, LOOK_TICKS = 40, WALK_TICKS = 100;
    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final MaidModernPerception perception;
    private final MaidSensingSession sensing;
    private final EnhancedNavigationController navigation;
    private Point destination;
    private EntityLivingBase watching;
    private long until, nextChoice, lastTick = Long.MIN_VALUE;
    private long samples, walkStarts, lookStarts;
    private boolean preferLook = true;

    public EnhancedAmbientController(EntityMaid maid, ModernFollowCoreActor actor, MaidModernPerception perception,
        MaidSensingSession sensing, EnhancedNavigationController navigation) {
        this.maid = maid;
        this.actor = actor;
        this.perception = perception;
        this.sensing = sensing;
        this.navigation = navigation;
    }

    public void offer(MaidIntentFrame frame) {
        long now = actor.worldGameTime();
        if (now < lastTick) {
            clearPlan();
            nextChoice = 0;
        }
        lastTick = now;
        if (frame.kindMask() != 0 || !permitted()) {
            sensing.cancel(MaidSensingBudget.Kind.AMBIENT);
            return;
        }
        var owner = temptingOwner();
        if (owner != null) {
            sensing.cancel(MaidSensingBudget.Kind.AMBIENT);
            frame.offer(Group.AMBIENT, Kind.BEG, "owner", 1, null);
            return;
        }
        if (watching != null || destination != null) {
            boolean legal = now < until && (watching != null ? legalLook(watching)
                : legalWalk(destination) && actor.distanceToBlockCenterSquared(destination) > 1
                    && navigation.outcome() != EnhancedNavigationController.Outcome.UNREACHABLE
                    && navigation.outcome() != EnhancedNavigationController.Outcome.RECOVERY_WAIT);
            if (legal) {
                offerPlan(frame);
                return;
            }
            clearPlan();
            nextChoice = now + CHOICE_INTERVAL;
        }
        if (now < nextChoice) {
            sensing.cancel(MaidSensingBudget.Kind.AMBIENT);
            return;
        }
        if (!sensing.acquire(MaidSensingBudget.Kind.AMBIENT)) return;
        nextChoice = now + CHOICE_INTERVAL;
        var visible = perception.visible();
        var target = visible == null ? null : visible.findClosest(this::legalLook);
        // 可见对象存在时轮换观察和闲逛，避免随机抽样长期遗漏一种表现
        if (target != null && preferLook) {
            preferLook = false;
            frame.offer(
                Group.AMBIENT,
                Kind.OBSERVE,
                target.getUniqueID()
                    .toString(),
                .3,
                target);
            return;
        }
        samples++;
        var point = actor.sampleEnhancedLand(5, 3);
        if (point != null) {
            var block = new Point(
                MathHelper.floor_double(point.x),
                MathHelper.floor_double(point.y),
                MathHelper.floor_double(point.z));
            if (legalWalk(block) && actor.distanceToBlockCenterSquared(block) > 1) {
                preferLook = true;
                offerWalk(frame, block);
                return;
            }
        }
        if (target != null) frame.offer(
            Group.AMBIENT,
            Kind.OBSERVE,
            target.getUniqueID()
                .toString(),
            .3,
            target);
    }

    private void offerPlan(MaidIntentFrame frame) {
        if (watching != null) frame.offer(
            Group.AMBIENT,
            Kind.OBSERVE,
            watching.getUniqueID()
                .toString(),
            .3,
            watching);
        else offerWalk(frame, destination);
    }

    private void offerWalk(MaidIntentFrame frame, Point point) {
        frame.offer(Group.AMBIENT, Kind.WANDER, point.x + ":" + point.y + ":" + point.z, .1, point);
    }

    private boolean permitted() {
        return actor.canBrainMoving() && !EnhancedRideController.stationary(maid)
            && !maid.getLeashed()
            && !actor.isSleeping()
            && !maid.isInWater()
            && maid.getItemUseState()
                .peek() == null;
    }

    private Owner temptingOwner() {
        if (maid.getHomeArea()
            .activity() == Activity.REST) return null;
        var owner = actor.visibleOwnerNow(6);
        var player = maid.getOwner();
        return owner != null && player != null
            && actor.ownerHoldsTemptation(owner)
            && maid.getHomeArea()
                .withinRestriction(
                    MathHelper.floor_double(player.posX),
                    MathHelper.floor_double(player.posY),
                    MathHelper.floor_double(player.posZ)) ? owner : null;
    }

    private boolean legalLook(EntityLivingBase target) {
        return target != maid && target != maid.riddenByEntity
            && target.worldObj == maid.worldObj
            && target.isEntityAlive()
            && maid.worldObj.loadedEntityList.contains(target)
            && (target instanceof EntityPlayer || target instanceof EntityMaid
                || target instanceof EntityWolf
                || target instanceof EntityOcelot)
            && (!(target instanceof EntityPlayer player)
                || !player.isPlayerSleeping() && (!(player instanceof net.minecraft.entity.player.EntityPlayerMP mp)
                    || mp.theItemInWorldManager.getGameType()
                        .getID() != 3))
            && maid.getDistanceSqToEntity(target) < 25
            && maid.getHomeArea()
                .withinRestriction(
                    MathHelper.floor_double(target.posX),
                    MathHelper.floor_double(target.posY),
                    MathHelper.floor_double(target.posZ))
            && maid.canEntityBeSeen(target);
    }

    private boolean legalWalk(Point point) {
        return actor.isEnhancedLandDestination(point);
    }

    public void execute(Intent intent) {
        if (!permitted()) return;
        switch (intent.kind) {
            case BEG -> {
                var owner = temptingOwner();
                if (owner == null) return;
                actor.setBegging(true);
                if (!actor.closerThan(owner, 2)) actor.setWalkAndLookTargetMemories(owner, .6F, 2);
                else {
                    actor.eraseWalkTarget();
                    actor.lookAtOwner(owner);
                }
            }
            case OBSERVE -> {
                var target = (EntityLivingBase) intent.payload;
                if (!legalLook(target)) return;
                if (watching != target) {
                    watching = target;
                    until = actor.worldGameTime() + LOOK_TICKS;
                    lookStarts++;
                }
                actor.lookAtAmbientEntity(target);
            }
            case WANDER -> {
                var point = (Point) intent.payload;
                if (!legalWalk(point)) return;
                if (!point.equals(destination)) {
                    destination = point;
                    until = actor.worldGameTime() + WALK_TICKS;
                    walkStarts++;
                }
                actor.setPositionWalkAndLook(point, .3F, 0);
            }
            default -> throw new IllegalArgumentException("Ambient intent required");
        }
    }

    private void clearPlan() {
        destination = null;
        watching = null;
        until = 0;
    }

    /** 无token时只释放本实例计划和排队身份；实际LOOK/PATH/姿态由actor所有者清理 */
    public void cancel() {
        clearPlan();
        sensing.cancel(MaidSensingBudget.Kind.AMBIENT);
    }

    public Point destination() {
        return destination;
    }

    public EntityLivingBase watching() {
        return watching;
    }

    public long samples() {
        return samples;
    }

    public long walkStarts() {
        return walkStarts;
    }

    public long lookStarts() {
        return lookStarts;
    }
}
