package com.tianluoqaq.tlmnh.common.ai.enhanced;

import java.util.Objects;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidModernPerception;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Hand;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Lease;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** 增强雪仗独立决定目标与动作；捡球和无攻击者弹射仍走已移植的平台原语 */
public final class EnhancedSnowballController {

    private static final int MAX_PLAY_TICKS = 1200, RETRY_TICKS = 40, PICKUP_DELAY = 25;
    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final MaidModernPerception perception;
    private final World world;
    private EntityLivingBase target;
    private Object run;
    private Lease handLease;
    private long nextPlay, nextThrow, nextPickup = Long.MAX_VALUE, until, lastTick = Long.MIN_VALUE;
    private int animationSequence = -1;

    public EnhancedSnowballController(EntityMaid maid, ModernFollowCoreActor actor, MaidModernPerception perception) {
        this.maid = maid;
        this.actor = actor;
        this.perception = perception;
        world = maid.worldObj;
    }

    public void offer(MaidIntentFrame frame) {
        long now = actor.worldGameTime();
        if (now < lastTick) nextPlay = 0;
        lastTick = now;
        if (frame.kindMask() != 0 || !permitted()
            || now < nextPlay
            || handLease != null && !maid.getItemUseState()
                .isCurrent(handLease))
            return;
        EntityLivingBase proposed = target;
        if (proposed != null) {
            if (now >= until || !legalTarget(proposed)
                || maid.getRNG()
                    .nextFloat() <= 1 / 32F)
                return;
        } else {
            var visible = perception.visible();
            proposed = visible == null ? null : visible.findClosest(this::legalTarget);
        }
        if (proposed != null) frame.offer(
            Group.AMBIENT,
            Kind.SNOWBALL,
            proposed.getUniqueID()
                .toString(),
            .5,
            proposed);
    }

    private boolean permitted() {
        var use = maid.getItemUseState()
            .peek();
        return maid.worldObj == world && actor.canBrainMoving()
            && !EnhancedRideController.stationary(maid)
            && !maid.getLeashed()
            && !actor.isSleeping()
            && maid.getTask() == MaidTask.IDLE
            && maid.getHomeArea()
                .activity() == Activity.WORK
            && actor.canSnowballFight()
            && actor.holdingSnowballOrEmpty()
            && (use == null || use == handLease);
    }

    private boolean legalTarget(EntityLivingBase value) {
        if (value == maid || value.worldObj != world
            || !value.isEntityAlive()
            || maid.getDistanceSqToEntity(value) >= 256
            || !maid.getHomeArea()
                .withinRestriction(
                    MathHelper.floor_double(value.posX),
                    MathHelper.floor_double(value.posY),
                    MathHelper.floor_double(value.posZ)))
            return false;
        String owner = maid.func_152113_b();
        boolean friendly = value instanceof EntityPlayer player && maid.isOwner(player)
            && world.playerEntities.contains(player)
            && (!(player instanceof EntityPlayerMP mp) || mp.theItemInWorldManager.getGameType()
                .getID() != 3)
            || value instanceof EntityMaid other && owner != null
                && owner.equals(other.func_152113_b())
                && world.getEntityByID(other.getEntityId()) == other;
        return friendly && maid.canEntityBeSeen(value);
    }

    public void execute(EntityLivingBase proposed) {
        if (!permitted() || !legalTarget(proposed)) return;
        if (target != proposed || run == null) {
            target = proposed;
            run = new Object();
            until = actor.worldGameTime() + MAX_PLAY_TICKS;
            var view = actor.combatTargetView(proposed);
            actor.rememberSnowballTarget(view);
            int previousAnimation = maid.getSnowballAnimation()
                .sequence();
            actor.prepareSnowballHand();
            if (maid.getSnowballAnimation()
                .sequence() != previousAnimation)
                animationSequence = maid.getSnowballAnimation()
                    .sequence();
            if (!actor.isCurrent()) return;
            nextThrow = actor.worldGameTime() + 2;
        }
        if (!acquireHand()) {
            cancel();
            return;
        }
        var currentRun = run;
        actor.eraseWalkTarget();
        var view = actor.combatTargetView(proposed);
        actor.lookAtAttackTarget(view);
        long now = actor.worldGameTime();
        if (now >= nextThrow) {
            actor.swingSnowballHand();
            actor.throwSnowballAt(view, () -> authorized(currentRun, proposed));
            if (!actor.isCurrent() || currentRun != run) return;
            nextThrow = now + 50
                + maid.getRNG()
                    .nextInt(50);
            nextPickup = now + PICKUP_DELAY;
        }
        if (now >= nextPickup) {
            actor.playPickUpSnowball();
            animationSequence = maid.getSnowballAnimation()
                .sequence();
            nextPickup = Long.MAX_VALUE;
        }
        if (actor.isPickingUpSnowball() && actor.nowMillis() - actor.snowballAnimationStartedAt() > 1750)
            actor.expireSnowballAnimation();
    }

    private boolean acquireHand() {
        if (handLease != null && maid.getItemUseState()
            .isCurrent(handLease)) return true;
        var main = maid.getHeldItem();
        Hand hand = snowball(main) ? Hand.MAIN : Hand.OFF;
        var item = hand == Hand.MAIN ? main : maid.getOffhandItem();
        if (!snowball(item)) return false;
        var runtime = maid.getBrainCoordinator()
            .peekPolicyRuntime();
        var token = runtime.getContext()
            .getToken();
        var originalRun = run;
        var owner = maid.func_152113_b();
        handLease = maid.getItemUseState()
            .tryAcquire(
                this,
                item,
                hand,
                com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Kind.SNOWBALL,
                () -> run == originalRun && originalRun != null
                    && maid.worldObj == world
                    && maid.isEntityAlive()
                    && !world.isRemote
                    && maid.getTask() == MaidTask.IDLE
                    && Objects.equals(owner, maid.func_152113_b())
                    && (hand == Hand.MAIN ? maid.getHeldItem() : maid.getOffhandItem()) == item
                    && snowball(item)
                    && maid.getBrainCoordinator()
                        .peekPolicyRuntime() == runtime
                    && runtime.isReady()
                    && runtime.getContext()
                        .isCurrent(token)
                    && !maid.getBrainCoordinator()
                        .isSuspended());
        return handLease != null && actor.isCurrent();
    }

    private static boolean snowball(ItemStack stack) {
        return stack != null && stack.stackSize > 0 && stack.getItem() == Items.snowball;
    }

    private boolean authorized(Object originalRun, EntityLivingBase proposed) {
        return run == originalRun && originalRun != null
            && actor.isCurrent()
            && target == proposed
            && maid.getItemUseState()
                .isCurrent(handLease)
            && permitted()
            && legalTarget(proposed);
    }

    public void cancel() {
        boolean wasActive = run != null || target != null || handLease != null;
        run = null;
        var old = target;
        target = null;
        var oldLease = handLease;
        handLease = null;
        if (wasActive) nextPlay = lastTick == Long.MIN_VALUE ? 0 : lastTick + RETRY_TICKS;
        nextPickup = Long.MAX_VALUE;
        try {
            maid.getItemUseState()
                .release(oldLease);
        } finally {
            if (old != null && maid.getModernCombatMemory()
                .target() == old) maid.getModernCombatMemory()
                    .target(null);
            if (animationSequence >= 0 && maid.getSnowballAnimation()
                .sequence() == animationSequence) maid.getSnowballAnimation()
                    .clear();
            animationSequence = -1;
        }
    }

    public EntityLivingBase target() {
        return target;
    }

    public Lease handLease() {
        return handLease;
    }
}
