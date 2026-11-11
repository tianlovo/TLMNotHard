package com.tianluoqaq.tlmnh.common.ai.enhanced.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.api.behavior.MaidActionAccess;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorContext;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorId;
import com.tianluoqaq.tlmnh.api.behavior.MaidHandAccess;
import com.tianluoqaq.tlmnh.api.behavior.MaidObservationView;
import com.tianluoqaq.tlmnh.api.behavior.MaidObservationView.EntitySnapshot;
import com.tianluoqaq.tlmnh.api.behavior.MaidObservationView.Vec3d;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCombatController;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedNavigationController;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Vec;
import com.tianluoqaq.tlmnh.common.ai.enhanced.support.MaidDomainGuard;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidModernPerception;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatAttributes;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Hand;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Kind;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Lease;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponCatalog;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidEquipmentTransfer;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** 外部行为模块窄能力视图宿主；严格实施回调相位门控、不可变快照导出与受验动作委托 */
public final class MaidPlatformBehaviorContext implements MaidBehaviorContext {

    private final MaidBehaviorId moduleId;
    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final MaidModernPerception perception;
    private final EnhancedNavigationController navigation;
    private final EnhancedCombatController combat;
    private final MaidPlatformHandLease handLeaseIssuer;
    private final MaidSessionCallbackScope scope;
    private final Supplier<Boolean> moduleHealthyGuard;

    private final ObservationAdapter observationAdapter = new ObservationAdapter();
    private final ActionAdapter actionAdapter = new ActionAdapter();
    private final IsolatedHandAccess handAdapter;

    public MaidPlatformBehaviorContext(MaidBehaviorId moduleId, EntityMaid maid, ModernFollowCoreActor actor,
        MaidModernPerception perception, EnhancedNavigationController navigation, EnhancedCombatController combat,
        MaidPlatformHandLease handLeaseIssuer, MaidSessionCallbackScope scope, Supplier<Boolean> moduleHealthyGuard) {
        this(
            moduleId,
            maid,
            actor,
            perception,
            navigation,
            combat,
            handLeaseIssuer,
            scope,
            moduleHealthyGuard,
            ignored -> {});
    }

    public MaidPlatformBehaviorContext(MaidBehaviorId moduleId, EntityMaid maid, ModernFollowCoreActor actor,
        MaidModernPerception perception, EnhancedNavigationController navigation, EnhancedCombatController combat,
        MaidPlatformHandLease handLeaseIssuer, MaidSessionCallbackScope scope, Supplier<Boolean> moduleHealthyGuard,
        Consumer<Throwable> asyncFaultHandler) {
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
        this.maid = Objects.requireNonNull(maid, "maid");
        this.actor = Objects.requireNonNull(actor, "actor");
        this.perception = Objects.requireNonNull(perception, "perception");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.combat = Objects.requireNonNull(combat, "combat");
        this.handLeaseIssuer = Objects.requireNonNull(handLeaseIssuer, "handLeaseIssuer");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.moduleHealthyGuard = Objects.requireNonNull(moduleHealthyGuard, "moduleHealthyGuard");
        this.handAdapter = new IsolatedHandAccess(
            this.moduleId,
            this.scope,
            this.moduleHealthyGuard,
            Objects.requireNonNull(asyncFaultHandler, "asyncFaultHandler"),
            this.handLeaseIssuer,
            this);
    }

    @Override
    public MaidObservationView observation() {
        scope.requireObservation(moduleId);
        return observationAdapter;
    }

    @Override
    public MaidActionAccess actions() {
        scope.requireExecute(moduleId);
        return actionAdapter;
    }

    @Override
    public MaidHandAccess hands() {
        scope.requireExecute(moduleId);
        return handAdapter;
    }

    public void releaseOwnedResources() {
        handAdapter.releaseOwnedResources();
    }

    private Entity resolveSameWorldEntity(UUID uniqueId, int entityId) {
        if (uniqueId == null || maid.worldObj == null) return null;
        var candidate = maid.worldObj.getEntityByID(entityId);
        if (candidate == null || candidate == maid
            || candidate.worldObj != maid.worldObj
            || candidate.dimension != maid.dimension
            || !candidate.isEntityAlive()
            || !uniqueId.equals(candidate.getUniqueID())) return null;
        return candidate;
    }

    private EntitySnapshot snapshotEntity(EntityLivingBase entity, boolean visibleFlag) {
        boolean friendly = entity instanceof EntityPlayer player && maid.isOwner(player)
            || entity instanceof EntityMaid other && maid.func_152113_b() != null
                && maid.func_152113_b()
                    .equals(other.func_152113_b())
            || maid.isOnSameTeam(entity);
        boolean hostile = !friendly && (entity instanceof IMob || entity == maid.getAITarget());
        return new EntitySnapshot(
            entity.getUniqueID(),
            entity.getEntityId(),
            new Vec3d(entity.posX, entity.posY, entity.posZ),
            new Vec3d(entity.motionX, entity.motionY, entity.motionZ),
            entity.getHealth(),
            entity.getMaxHealth(),
            maid.getDistanceSqToEntity(entity),
            hostile,
            friendly,
            visibleFlag);
    }

    private final class ObservationAdapter implements MaidObservationView {

        private void check() {
            scope.requireObservation(moduleId);
        }

        @Override
        public boolean isCurrent() {
            return Boolean.TRUE.equals(moduleHealthyGuard.get()) && scope.isObservationCurrent(moduleId)
                && actor.isCurrent();
        }

        @Override
        public long worldTick() {
            check();
            return actor.worldGameTime();
        }

        @Override
        public int entityTicks() {
            check();
            return maid.ticksExisted;
        }

        @Override
        public Vec3d position() {
            check();
            return new Vec3d(maid.posX, maid.posY, maid.posZ);
        }

        @Override
        public Vec3d velocity() {
            check();
            return new Vec3d(maid.motionX, maid.motionY, maid.motionZ);
        }

        @Override
        public float health() {
            check();
            return maid.getHealth();
        }

        @Override
        public float maxHealth() {
            check();
            return maid.getMaxHealth();
        }

        @Override
        public int airSupply() {
            check();
            return actor.airSupply();
        }

        @Override
        public int hunger() {
            check();
            return maid.getHunger();
        }

        @Override
        public int favorability() {
            check();
            return maid.getFavorability();
        }

        @Override
        public MaidTask task() {
            check();
            return maid.getTask();
        }

        @Override
        public MaidSchedule schedule() {
            check();
            return maid.getSchedule();
        }

        @Override
        public Activity activity() {
            check();
            return maid.getHomeArea()
                .activity();
        }

        @Override
        public boolean isSitting() {
            check();
            return maid.isSitting();
        }

        @Override
        public boolean isRiding() {
            check();
            return maid.ridingEntity != null;
        }

        @Override
        public boolean isLeashed() {
            check();
            return maid.getLeashed();
        }

        @Override
        public boolean isSleeping() {
            check();
            return actor.isSleeping();
        }

        @Override
        public boolean isInWater() {
            check();
            return maid.isInWater();
        }

        @Override
        public boolean isHurt() {
            check();
            return maid.hurtTime > 0;
        }

        @Override
        public boolean canBrainMove() {
            check();
            return actor.canBrainMoving();
        }

        @Override
        public boolean isHomeEnabled() {
            check();
            return maid.getHomeArea()
                .enabled();
        }

        @Override
        public boolean isWithinActiveDomain(double x, double y, double z) {
            check();
            return MaidDomainGuard.isPointWithinActiveDomain(maid, x, y, z);
        }

        @Override
        public EntitySnapshot owner() {
            check();
            var owner = maid.getOwner();
            if (owner == null || owner.worldObj != maid.worldObj
                || owner.dimension != maid.dimension
                || !owner.isEntityAlive()
                || owner instanceof EntityPlayerMP mp && mp.theItemInWorldManager.getGameType()
                    .getID() == 3)
                return null;
            return snapshotEntity(owner, maid.canEntityBeSeen(owner));
        }

        @Override
        public List<EntitySnapshot> visibleEntities() {
            check();
            var vis = perception.visible();
            if (vis == null) return Collections.emptyList();
            var list = new ArrayList<EntitySnapshot>();
            for (var entity : perception.nearby()) {
                if (entity != null && entity.isEntityAlive() && vis.contains(entity)) {
                    list.add(snapshotEntity(entity, true));
                }
            }
            return Collections.unmodifiableList(list);
        }

        @Override
        public List<EntitySnapshot> nearbyEntities() {
            check();
            var raw = perception.nearby();
            if (raw.isEmpty()) return Collections.emptyList();
            var vis = perception.visible();
            var list = new ArrayList<EntitySnapshot>(raw.size());
            for (var entity : raw) {
                if (entity != null && entity.isEntityAlive()) {
                    list.add(snapshotEntity(entity, vis != null && vis.contains(entity)));
                }
            }
            return Collections.unmodifiableList(list);
        }

        @Override
        public WeaponCapabilitySnapshot weaponCapability() {
            check();
            long now = actor.worldGameTime();
            var obs = actor.combatObservation();
            double reach = MaidCombatAttributes.entityReach(maid) + maid.getFavorabilityManager()
                .getAttackDistancePlusByPoint(maid.getFavorability());
            var profile = maid.getCombatWeaponProfile();
            boolean melee = obs.supportedMelee() || profile.melee();
            boolean bow = obs.supportedBow() || profile.bow();
            return new WeaponCapabilitySnapshot(
                melee,
                bow,
                obs.observedAmmunition(now),
                reach,
                maid.getCombatCadence()
                    .remainingTicks());
        }

        @Override
        public List<ProjectileThreatSnapshot> projectileThreats() {
            check();
            CombatSnapshot snapshot = combat.lastSnapshot();
            long now = actor.worldGameTime();
            if (snapshot == null || snapshot.projectiles.isEmpty() || now < snapshot.tick || now - snapshot.tick > 2)
                return Collections.emptyList();
            var list = new ArrayList<ProjectileThreatSnapshot>(snapshot.projectiles.size());
            for (var arrow : snapshot.projectiles) {
                list.add(
                    new ProjectileThreatSnapshot(
                        arrow.id,
                        new Vec3d(arrow.position.x, arrow.position.y, arrow.position.z),
                        new Vec3d(arrow.velocity.x, arrow.velocity.y, arrow.velocity.z),
                        arrow.observedAt));
            }
            return Collections.unmodifiableList(list);
        }
    }

    private final class ActionAdapter implements MaidActionAccess {

        private void check() {
            scope.requireExecute(moduleId);
        }

        @Override
        public boolean walkToBlock(int x, int y, int z, float speed, int closeEnough) {
            check();
            if (!Float.isFinite(speed) || speed <= 0.0F) return false;
            if (!actor.canBrainMoving() || !MaidDomainGuard.isPointWithinActiveDomain(maid, x + .5, y, z + .5))
                return false;
            float clampedSpeed = Math.max(0.1F, Math.min(1.0F, speed));
            int clampedClose = Math.max(0, closeEnough);
            actor.setPositionWalkAndLook(new Point(x, y, z), clampedSpeed, clampedClose);
            return true;
        }

        @Override
        public boolean walkToEntity(UUID uniqueId, int entityId, float speed, int closeEnough) {
            check();
            if (!Float.isFinite(speed) || speed <= 0.0F) return false;
            if (!actor.canBrainMoving()) return false;
            var target = resolveSameWorldEntity(uniqueId, entityId);
            if (target == null
                || !MaidDomainGuard.isPointWithinActiveDomain(maid, target.posX, target.posY, target.posZ))
                return false;
            float clampedSpeed = Math.max(0.1F, Math.min(1.0F, speed));
            int clampedClose = Math.max(0, closeEnough);
            actor.setEntityWalkAndLook(target, clampedSpeed, clampedClose);
            return true;
        }

        @Override
        public void stopWalk() {
            check();
            actor.eraseWalkTarget();
            actor.stopNavigation();
        }

        @Override
        public void lookAt(double x, double y, double z) {
            check();
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
            actor.lookAtCombatPoint(new Vec(x, y, z));
        }

        @Override
        public boolean lookAtEntity(UUID uniqueId, int entityId) {
            check();
            var target = resolveSameWorldEntity(uniqueId, entityId);
            if (!(target instanceof EntityLivingBase living)) return false;
            actor.lookAtAmbientEntity(living);
            return true;
        }

        @Override
        public void clearLook() {
            check();
            actor.eraseLookTarget();
        }

        @Override
        public NavigationOutcome lastNavigationOutcome() {
            check();
            return switch (navigation.outcome()) {
                case IDLE -> NavigationOutcome.IDLE;
                case ARRIVED -> NavigationOutcome.ARRIVED;
                case RUNNING -> NavigationOutcome.RUNNING;
                case PARTIAL_PATH -> NavigationOutcome.PARTIAL_PATH;
                case WAITING_BUDGET -> NavigationOutcome.WAITING_BUDGET;
                case NOT_NAVIGABLE -> NavigationOutcome.NOT_NAVIGABLE;
                case UNREACHABLE -> NavigationOutcome.UNREACHABLE;
                case COOLDOWN -> NavigationOutcome.COOLDOWN;
                case RECOVERY_WAIT -> NavigationOutcome.RECOVERY_WAIT;
            };
        }

        @Override
        public boolean selectHotbarOrInventoryWeapon(int maidInventorySlot) {
            check();
            if (maidInventorySlot < 0 || maidInventorySlot >= MaidInventory.BACKPACK_SIZE) return false;
            if (!maid.isTamed() || maid.getCombatHitReceipt()
                .blocksTransfer()) return false;
            if (maidInventorySlot == MaidInventory.MAIN_HAND) {
                var held = maid.getHeldItem();
                return held != null && MaidWeaponCatalog.inspect(held)
                    .supported();
            }
            var stack = maid.getMaidInventory()
                .getStackInSlot(maidInventorySlot);
            if (stack == null || !MaidWeaponCatalog.inspect(stack)
                .supported()) return false;
            var before = MaidEquipmentTransfer.capture(maid);
            if (before == null) return false;
            var result = MaidEquipmentTransfer.exchange(
                maid,
                before,
                maidInventorySlot,
                () -> Boolean.TRUE.equals(moduleHealthyGuard.get()) && actor.isCurrent());
            return result.changed();
        }

        @Override
        public boolean requestMeleeAttack(UUID targetUniqueId, int targetEntityId) {
            check();
            if (!maid.isTamed() || maid.getTask() != MaidTask.ATTACK
                || !MaidDomainGuard.isMaidWithinDomain(maid, actor, MaidDomainGuard.DEFAULT_OWNER_DOMAIN_RADIUS))
                return false;
            var entity = resolveSameWorldEntity(targetUniqueId, targetEntityId);
            if (!(entity instanceof EntityLivingBase living)) return false;
            if (!MaidDomainGuard.isPointWithinActiveDomain(maid, living.posX, living.posY, living.posZ)) return false;
            var targetView = actor.combatTargetView(living);
            if (!actor.canAttack(targetView) || !actor.withinMeleeRange(targetView) || actor.attackCoolingDown())
                return false;
            if (maid.getModernCombatMemory()
                .target() != living) {
                if (!actor.setAttackTarget(targetView) || !actor.isCurrent()) return false;
            }
            long beforeAccepted = actor.meleeNativeAccepted();
            actor.hurtTarget(targetView, () -> Boolean.TRUE.equals(moduleHealthyGuard.get()) && actor.isCurrent());
            return actor.meleeNativeAccepted() > beforeAccepted;
        }

        @Override
        public boolean requestShieldGuard() {
            check();
            if (!actor.canUseShield()) return false;
            var currentTarget = actor.attackTarget();
            if (currentTarget == null) return false;
            actor.startUsingShield(currentTarget);
            return actor.isCurrent() && actor.shieldBehaviorRunning();
        }

        @Override
        public void stopShieldGuard() {
            check();
            if (actor.shieldBehaviorRunning()) actor.stopUsingShield();
        }
    }

    /** 外部手部访问隔离包装器；将延迟续期回调中的模块级异常归属到原槽位并熔断 */
    public static final class IsolatedHandAccess implements MaidHandAccess {

        public interface Backend {

            Lease tryAcquireCopyValidated(Object owner, Hand hand, Kind kind, Predicate<ItemStack> itemValidator,
                BooleanSupplier extraGuard);

            boolean isCurrent(Lease lease);

            boolean release(Lease lease);
        }

        private final MaidBehaviorId moduleId;
        private final MaidSessionCallbackScope scope;
        private final Supplier<Boolean> moduleHealthyGuard;
        private final Consumer<Throwable> asyncFaultHandler;
        private final Backend backend;
        private final Object leaseOwner;
        private final List<Lease> ownedLeases = new ArrayList<>();

        public IsolatedHandAccess(MaidBehaviorId moduleId, MaidSessionCallbackScope scope,
            Supplier<Boolean> moduleHealthyGuard, Consumer<Throwable> asyncFaultHandler, Backend backend,
            Object leaseOwner) {
            this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
            this.scope = Objects.requireNonNull(scope, "scope");
            this.moduleHealthyGuard = Objects.requireNonNull(moduleHealthyGuard, "moduleHealthyGuard");
            this.asyncFaultHandler = Objects.requireNonNull(asyncFaultHandler, "asyncFaultHandler");
            this.backend = Objects.requireNonNull(backend, "backend");
            this.leaseOwner = Objects.requireNonNull(leaseOwner, "leaseOwner");
        }

        private void check() {
            scope.requireExecute(moduleId);
        }

        @Override
        public Lease tryAcquireHand(Hand hand, Kind kind, Predicate<ItemStack> itemValidator,
            BooleanSupplier extraGuard) {
            check();
            Objects.requireNonNull(hand, "hand");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(itemValidator, "itemValidator");
            Predicate<ItemStack> guardedValidator = stackCopy -> {
                if (!Boolean.TRUE.equals(moduleHealthyGuard.get())) return false;
                try {
                    return itemValidator.test(stackCopy);
                } catch (RuntimeException | LinkageError | AssertionError failure) {
                    asyncFaultHandler.accept(failure);
                    return false;
                }
            };
            BooleanSupplier guardedExtra = () -> {
                if (!Boolean.TRUE.equals(moduleHealthyGuard.get())) return false;
                if (extraGuard == null) return true;
                try {
                    return extraGuard.getAsBoolean();
                } catch (RuntimeException | LinkageError | AssertionError failure) {
                    asyncFaultHandler.accept(failure);
                    return false;
                }
            };
            var lease = backend.tryAcquireCopyValidated(leaseOwner, hand, kind, guardedValidator, guardedExtra);
            if (lease != null && !ownedLeases.contains(lease)) {
                ownedLeases.add(lease);
            }
            return lease;
        }

        @Override
        public boolean isCurrent(Lease lease) {
            check();
            return lease != null && ownedLeases.contains(lease) && backend.isCurrent(lease);
        }

        @Override
        public boolean releaseHand(Lease lease) {
            check();
            if (lease == null || !ownedLeases.remove(lease)) return false;
            return backend.release(lease);
        }

        public void releaseOwnedResources() {
            for (int i = ownedLeases.size() - 1; i >= 0; i--) {
                backend.release(ownedLeases.get(i));
            }
            ownedLeases.clear();
        }
    }
}
