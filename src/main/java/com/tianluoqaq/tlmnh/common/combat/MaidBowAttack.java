package com.tianluoqaq.tlmnh.common.combat;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.minecraft.block.material.Material;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.combat.MaidCombatTargeting;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorldBudget;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.WorkComponent;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Hand;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Kind;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Lease;
import com.tianluoqaq.tlmnh.common.combat.ballistics.ArrowInterceptSolver;
import com.tianluoqaq.tlmnh.common.combat.ballistics.ArrowPhysics;
import com.tianluoqaq.tlmnh.common.combat.ballistics.BallisticVector;
import com.tianluoqaq.tlmnh.common.combat.ballistics.BallisticsIds;
import com.tianluoqaq.tlmnh.common.combat.ballistics.MaidGravityFreeArrow;
import com.tianluoqaq.tlmnh.common.combat.ballistics.NativeArrowMedium;
import com.tianluoqaq.tlmnh.common.combat.ballistics.ObservedTargetMotion;
import com.tianluoqaq.tlmnh.common.combat.projectile.MaidProjectileImpactProtection;
import com.tianluoqaq.tlmnh.common.combat.spi.ProjectilePhysicsProvider;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

/** 原生弓箭执行器：观察、拉弓、真实弹道安全检查和成功生成后的资源结算 */
public final class MaidBowAttack {

    public enum ModernFlightProfile {
        NATIVE_STATIC_COMPENSATION,
        NATIVE_MOTION_COMPENSATION,
        SOURCE_ORIGINAL,
        NATIVE_LAST_SAMPLE_COMPENSATION
    }

    public enum Status {
        IDLE,
        DRAWING,
        READY,
        FIRED,
        NO_AMMO,
        NO_SOLUTION,
        BLOCKED,
        UNLOADED,
        LIQUID,
        SPAWN_REJECTED,
        PHYSICS_UNAVAILABLE,
        QUERY_LIMIT
    }

    private final EntityMaid maid;
    private final ObservedTargetMotion motion = new ObservedTargetMotion();
    private EntityLivingBase observed;
    private ItemStack drawingBow;
    private long tick, retryAt;
    private int drawTicks;
    private long shots, rejectedShots;
    private Status status = Status.IDLE;
    private ArrowInterceptSolver.Solution lastSolution;
    private ArrowPhysics lastPhysics;
    private EntityArrow lastArrow;
    private Refusal lastRefusal;
    private boolean firing;
    private Lease useLease;
    private long generation;
    private ModernFlightProfile modernFlightProfile = ModernFlightProfile.NATIVE_STATIC_COMPENSATION;

    public MaidBowAttack(EntityMaid maid) {
        this(maid, ModernFlightProfile.NATIVE_STATIC_COMPENSATION);
    }

    public MaidBowAttack(EntityMaid maid, ModernFlightProfile profile) {
        this.maid = Objects.requireNonNull(maid, MaidCombatIds.ID_MAID);
        modernFlightProfile = Objects.requireNonNull(profile, MaidCombatIds.ID_PROFILE);
    }

    public ModernFlightProfile flightProfile() {
        return modernFlightProfile;
    }

    public static boolean hasBow(EntityMaid maid) {
        var held = maid.getHeldItem();
        return held != null && held.stackSize > 0 && held.getItem() == Items.bow;
    }

    public boolean hasAmmunition() {
        return arrowSlot() >= 0;
    }

    /** 只检查原释放费用是否可付；实际生成前仍逐项收费和重验 */
    public static boolean hasReleaseBudget(CombatWorkQuota quota) {
        return quota.remaining(CombatWorkQuota.Work.FINAL_CHECK) >= MaidCombatTuning.BOW_ATTACK_SPEND_SECOND_PARAM
            && quota.remaining(CombatWorkQuota.Work.RAY) >= MaidCombatTuning.EQUIPMENT_EPOCH_EQUIPMENT_PARAM
            && quota.remaining(CombatWorkQuota.Work.BALLISTIC) > 0;
    }

    public void observe(EntityLivingBase target, boolean visible, long tick) {
        this.tick = tick;
        if (!visible || target == null) {
            motion.clear();
            observed = null;
            interrupt();
            return;
        }
        if (observed != target) interrupt();
        observed = target;
        motion.observe(target, tick, aimPoint(target));
    }

    public boolean prepare() {
        if (firing) return false;
        if (!hasBow(maid) || !hasAmmunition() || observed == null) {
            interrupt();
            status = Status.NO_AMMO;
            return false;
        }
        if (tick < retryAt) return false;
        if (drawingBow != maid.getHeldItem()) {
            interrupt();
            drawingBow = maid.getHeldItem();
        }
        if (useLease != null && !maid.getItemUseState()
            .isCurrent(useLease)) {
            interrupt();
            drawingBow = maid.getHeldItem();
        }
        var expectedBow = drawingBow;
        long expectedGeneration = generation;
        useLease = maid.getItemUseState()
            .tryAcquire(
                this,
                expectedBow,
                Hand.MAIN,
                Kind.BOW,
                () -> generation == expectedGeneration && drawingBow == expectedBow
                    && maid.getHeldItem() == expectedBow
                    && canHoldBowUse());
        if (useLease == null) {
            drawTicks = MaidCombatTuning.MAX_INITIAL_VALUE;
            status = Status.BLOCKED;
            return false;
        }
        drawTicks++;
        status = drawTicks >= MaidCombatTuning.DRAW_TICKS ? Status.READY : Status.DRAWING;
        return drawTicks >= MaidCombatTuning.DRAW_TICKS;
    }

    public boolean fire(EntityLivingBase target) {
        if (firing) return false;
        lastRefusal = null;
        maid.getBrainCoordinator()
            .getTelemetry()
            .releaseAttempt(target);
        firing = true;
        try {
            return fireOnce(target);
        } finally {
            firing = false;
        }
    }

    private boolean fireOnce(EntityLivingBase target) {
        if (!CompatServices.projectilePhysics()
            .arrowGravityAvailable()) {
            status = Status.PHYSICS_UNAVAILABLE;
            return false;
        }
        if (!canFire(target) || target != observed
            || drawTicks < MaidCombatTuning.DRAW_TICKS
            || drawingBow != maid.getHeldItem()
            || !maid.getItemUseState()
                .isCurrent(useLease)
            || !hasBow(maid)
            || arrowSlot() < 0) return false;
        return release(
            target,
            drawingBow,
            arrowSlot(),
            () -> canFire(target) && target == observed
                && drawingBow == maid.getHeldItem()
                && maid.getItemUseState()
                    .isCurrent(useLease),
            true,
            MaidCombatTuning.BOW_ATTACK_DOMAIN_FACTOR);
    }

    /** 原行为已stopUsingItem后调用，不要求原型draw20/MAIN租约/12格/禁止骑乘等准入 */
    public boolean fireForModern(EntityLivingBase target, float charge, BooleanSupplier admitted) {
        if (firing) return false;
        if (maid.isUnifiedCombat() && (!maid.getCombatCadence()
            .ready() || maid.getCombatHitReceipt()
                .blocksTransfer()))
            return false;
        lastRefusal = null;
        if (!CompatServices.projectilePhysics()
            .arrowGravityAvailable() || !MaidProjectileImpactProtection.available(maid)) {
            status = Status.PHYSICS_UNAVAILABLE;
            return false;
        }
        if (firing || !admitted.getAsBoolean()) return false;
        int slot = modernArrowSlot(maid);
        if (slot < 0) return false;
        maid.getBrainCoordinator()
            .getTelemetry()
            .releaseAttempt(target);
        firing = true;
        try {
            return release(target, maid.getHeldItem(), slot, admitted, false, charge);
        } finally {
            firing = false;
        }
    }

    /** 只供显式开发对照，不改变默认玩法的箭物理，也不代表完整忠实策略已经准入 */
    public void setFlightProfileForDevelopment(ModernFlightProfile profile) {
        if (!DevelopmentFlags.isDevCommandsEnabled() || firing)
            throw new IllegalStateException(MaidCombatDiagnostics.MSG_DEVELOPMENT_FLIGHT_PROFILE_CANNOT_CHANGE);
        interrupt();
        modernFlightProfile = Objects.requireNonNull(profile, MaidCombatIds.ID_PROFILE);
    }

    private boolean release(EntityLivingBase target, ItemStack bow, int slot, BooleanSupplier admitted,
        boolean prototype, float charge) {
        if (bow == null || slot < 0 || !admitted.getAsBoolean()) return false;
        if (maid.isUnifiedCombat()) {
            var quota = CombatWorldBudget
                .heartbeat(maid.worldObj, maid.getUniqueID(), maid.worldObj.getTotalWorldTime(), false);
            var work = CombatWorkQuota.Work.FINAL_CHECK;
            if (!quota.spend(work, MaidCombatTuning.BOW_ATTACK_SPEND_SECOND_PARAM)
                || !quota.spend(CombatWorkQuota.Work.RAY, MaidCombatTuning.EQUIPMENT_EPOCH_EQUIPMENT_PARAM)
                || !quota.spend(CombatWorkQuota.Work.BALLISTIC)) return reject(Status.SPAWN_REJECTED);
            var profile = maid.getCombatWeaponProfile();
            if (!profile.bow() || maid.getCombatHitReceipt()
                .blocksTransfer()
                || !maid.getEquipmentEpoch()
                    .bindAttackModifiers(bow, profile, quota)
                || !maid.getEquipmentEpoch()
                    .acknowledged(bow)
                || !maid.getCombatCadence()
                    .ready())
                return reject(Status.SPAWN_REJECTED);
        }
        var world = maid.worldObj;
        var provider = world.provider;
        int dimension = maid.dimension;
        var identity = maid.getUniqueID();
        double launchX = maid.posX, launchY = maid.posY, launchZ = maid.posZ;
        float launchEyeHeight = maid.getEyeHeight();
        var inventory = maid.getMaidInventory();
        var ammunition = inventory.getStackInSlot(slot);
        if (ammunition == null || ammunition.stackSize <= 0 || ammunition.getItem() != Items.arrow) return false;
        var bowBefore = bow.copy();
        var ammunitionBefore = ammunition.copy();
        long transaction = generation;
        BooleanSupplier current = () -> admitted.getAsBoolean() && generation == transaction
            && maid.worldObj == world
            && world.provider == provider
            && maid.dimension == dimension
            && maid.posX == launchX
            && maid.posY == launchY
            && maid.posZ == launchZ
            && maid.getEyeHeight() == launchEyeHeight
            && identity.equals(maid.getUniqueID())
            && maid.getHeldItem() == bow
            && inventory.getStackInSlot(slot) == ammunition
            && ItemStack.areItemStacksEqual(bow, bowBefore)
            && ItemStack.areItemStacksEqual(ammunition, ammunitionBefore);
        lastPhysics = null;
        boolean original = !prototype && modernFlightProfile == ModernFlightProfile.SOURCE_ORIGINAL;
        var origin = new BallisticVector(
            launchX,
            launchY + launchEyeHeight
                - (original ? (double) MaidCombatTuning.BOW_ATTACK_BALLISTIC_VECTOR_SECOND_FACTOR
                    : MaidCombatTuning.BOW_ATTACK_BALLISTIC_VECTOR_SECOND_FACTOR_2),
            launchZ);
        var telemetry = maid.getBrainCoordinator()
            .getTelemetry();
        boolean recording = telemetry.isEnabled();
        EntityArrow arrow;
        ProjectilePhysicsProvider.ArrowLaunchSample launch;
        NativeAim nativeAim = null;
        if (original) {
            var sourceArrow = new MaidGravityFreeArrow(world, origin.x, origin.y, origin.z);
            sourceArrow.initializeSourceShot(
                new BallisticVector(
                    target.posX - maid.posX,
                    (target.posY + target.getEyeHeight()) - (maid.posY + maid.getEyeHeight()),
                    target.posZ - maid.posZ),
                maid.getDistanceToEntity(target),
                charge,
                maid.worldObj.difficultySetting.getDifficultyId());
            arrow = sourceArrow;
            lastSolution = null;
            launch = CompatServices.projectilePhysics()
                .sampleArrowLaunch(arrow);
        } else {
            // 查询必须针对真正将要生成的箭，不用代理箭或主世界固定值代替实际provider
            arrow = new EntityArrow(world, origin.x, origin.y, origin.z);
            arrow.shootingEntity = maid;
            launch = CompatServices.projectilePhysics()
                .sampleArrowLaunch(arrow);
            if (launch == null || !launch.isCurrent(arrow) || !current.getAsBoolean()) {
                arrow.setDead();
                status = Status.PHYSICS_UNAVAILABLE;
                return false;
            }
            long startedAt = recording ? System.nanoTime() : MaidCombatTuning.MAX_INITIAL_VALUE;
            try {
                nativeAim = nativeAim(
                    arrow,
                    target,
                    launch,
                    () -> launch.isCurrent(arrow) && current.getAsBoolean(),
                    prototype);
                lastSolution = nativeAim.solution;
                lastPhysics = nativeAim.physics;
            } finally {
                if (recording)
                    telemetry.work(MaidCombatIds.ID_BOW_SOLVER, WorkComponent.SOLVER, System.nanoTime() - startedAt);
            }
            if (nativeAim.status != Status.READY) {
                var refusal = (nativeAim.status == Status.BLOCKED || nativeAim.status == Status.NO_SOLUTION)
                    && current.getAsBoolean() ? new Refusal(maid, target, bow, slot, nativeAim) : null;
                arrow.setDead();
                boolean rejected = reject(nativeAim.status);
                lastRefusal = refusal;
                return rejected;
            }
        }
        if (!MaidProjectileImpactProtection.admit(maid, arrow)) {
            arrow.setDead();
            return false;
        }
        if (launch == null || !launch.isCurrent(arrow) || !current.getAsBoolean()) {
            arrow.setDead();
            return false;
        }
        arrow.shootingEntity = maid;

        boolean infinite = EnchantmentHelper.getEnchantmentLevel(Enchantment.infinity.effectId, bow) > 0;
        arrow.canBePickedUp = infinite
            ? original ? MaidCombatTuning.MAX_INITIAL_VALUE : MaidCombatTuning.TARGET_SIZE_THRESHOLD
            : MaidCombatTuning.MAX_SINGLE_UNIT;
        arrow.setIsCritical(prototype);
        int power = EnchantmentHelper.getEnchantmentLevel(Enchantment.power.effectId, bow);
        if (power > 0) arrow
            .setDamage(arrow.getDamage() + power * MaidCombatTuning.HALF_FACTOR_D + MaidCombatTuning.HALF_FACTOR_D);
        int punch = EnchantmentHelper.getEnchantmentLevel(Enchantment.punch.effectId, bow);
        if (punch > 0) arrow.setKnockbackStrength(punch);
        if (EnchantmentHelper.getEnchantmentLevel(Enchantment.flame.effectId, bow) > 0)
            arrow.setFire(MaidCombatTuning.BOW_ATTACK_SET_FIRE_FIRST_PARAM);
        if (!prototype) arrow.setDamage(
            arrow.getDamage() * (float) (maid.getEntityAttribute(SharedMonsterAttributes.attackDamage)
                .getBaseValue() / MaidCombatTuning.MIDPOINT_DIVISOR_D));
        var arrowBefore = snapshot(arrow);
        boolean criticalBefore = arrow.getIsCritical();
        var boundsBefore = new double[] { arrow.boundingBox.minX, arrow.boundingBox.minY, arrow.boundingBox.minZ,
            arrow.boundingBox.maxX, arrow.boundingBox.maxY, arrow.boundingBox.maxZ };
        if (maid.isUnifiedCombat() && (!maid.getEquipmentEpoch()
            .acknowledged(bow) || maid.getCombatHitReceipt()
                .blocksTransfer()
            || !maid.getCombatCadence()
                .commit(
                    Math.max(
                        MaidCombatTuning.MAX_SINGLE_UNIT,
                        (long) maid.getEntityAttribute(MaidCombatAttributes.SHOOT_COOLDOWN)
                            .getAttributeValue())))) {
            arrow.setDead();
            return reject(Status.SPAWN_REJECTED);
        }
        if (!world.spawnEntityInWorld(arrow)) {
            arrow.setDead();
            return reject(Status.SPAWN_REJECTED);
        }
        // spawn事件允许重入；若事件改了库存，撤销箭实体，不能扣另一组资源或免费射击
        var arrowAfter = snapshot(arrow);
        var boundsAfter = new double[] { arrow.boundingBox.minX, arrow.boundingBox.minY, arrow.boundingBox.minZ,
            arrow.boundingBox.maxX, arrow.boundingBox.maxY, arrow.boundingBox.maxZ };
        if (arrow.isDead || arrow.shootingEntity != maid
            || arrow.getIsCritical() != criticalBefore
            || !arrowBefore.equals(arrowAfter)
            || !Arrays.equals(boundsBefore, boundsAfter)
            || !launch.isCurrent(arrow)
            || !current.getAsBoolean()
            || nativeAim != null && !stillSafe(nativeAim, arrow, target)) {
            arrow.setDead();
            return reject(Status.SPAWN_REJECTED);
        }
        if (!infinite) maid.getMaidInventory()
            .decrStackSize(slot, MaidCombatTuning.MAX_SINGLE_UNIT);
        bow.damageItem(MaidCombatTuning.MAX_SINGLE_UNIT, maid);
        if (bow.stackSize <= 0) maid.setCurrentItemOrArmor(MaidCombatTuning.MAX_INITIAL_VALUE, null);
        maid.worldObj.playSoundAtEntity(
            maid,
            MaidCombatIds.ID_RANDOM_BOW,
            MaidCombatTuning.MAX_SINGLE_UNIT,
            original ? MaidCombatTuning.BOW_ATTACK_DOMAIN_FACTOR / (maid.getRNG()
                .nextFloat() * MaidCombatTuning.BOW_ATTACK_DOMAIN_FACTOR_2
                + MaidCombatTuning.BOW_ATTACK_DOMAIN_FACTOR_3) : MaidCombatTuning.BOW_ATTACK_DOMAIN_FACTOR);
        shots++;
        lastArrow = arrow;
        telemetry.releaseConfirmed(arrow, target);
        interrupt();
        status = Status.FIRED;
        return true;
    }

    private static NBTTagCompound snapshot(EntityArrow arrow) {
        var tag = new NBTTagCompound();
        arrow.writeToNBT(tag);
        // 原生Entity.writeToNBT复用ForgeData，不能让出生回调原地修改已捕获的基线
        return (NBTTagCompound) tag.copy();
    }

    private Status safePath(EntityArrow arrow, EntityLivingBase target, double flightTicks, ArrowPhysics physics,
        NativeArrowMedium medium, NativeAim result) {
        var state = new ArrowPhysics.State(
            new BallisticVector(arrow.posX, arrow.posY, arrow.posZ),
            new BallisticVector(arrow.motionX, arrow.motionY, arrow.motionZ));
        for (int step = 0; step < Math.ceil(flightTicks); step++) {
            var sample = physics.mediumAt(state.position);
            var moving = state.velocity.add(sample.push);
            var end = state.position.add(moving.scale(Math.min(MaidCombatTuning.MAX_SINGLE_UNIT, flightTicks - step)));
            int minX = MathHelper.floor_double(Math.min(state.position.x, end.x) - MaidCombatTuning.BOW_ATTACK_MIN_X);
            int maxX = MathHelper.floor_double(Math.max(state.position.x, end.x) + MaidCombatTuning.BOW_ATTACK_MIN_X);
            int minY = MathHelper.floor_double(Math.min(state.position.y, end.y) - MaidCombatTuning.BOW_ATTACK_MIN_Y);
            int maxY = MathHelper.floor_double(Math.max(state.position.y, end.y) + 1);
            int minZ = MathHelper.floor_double(Math.min(state.position.z, end.z) - MaidCombatTuning.BOW_ATTACK_MIN_X);
            int maxZ = MathHelper.floor_double(Math.max(state.position.z, end.z) + MaidCombatTuning.BOW_ATTACK_MIN_X);
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                if (!maid.worldObj.getChunkProvider()
                    .chunkExists(x >> MaidCombatTuning.CHUNK_SECTION_SHIFT, z >> MaidCombatTuning.CHUNK_SECTION_SHIFT))
                    return Status.UNLOADED;
                for (int y = minY; y <= maxY; y++) {
                    if (!maid.worldObj.blockExists(x, y, z)) return Status.UNLOADED;
                    var block = medium.getBlock(x, y, z);
                    Material material = block.getMaterial();
                    if (material.isLiquid() && block != Blocks.water && block != Blocks.flowing_water)
                        return Status.LIQUID;
                }
            }
            var from = vec(state.position);
            var to = vec(end);
            // 原生射线会原地推进起点；实体碰撞仍须检查完整tick线段
            if (maid.worldObj.func_147447_a(vec(state.position), vec(end), false, true, false) != null)
                return Status.BLOCKED;
            var bounds = AxisAlignedBB
                .getBoundingBox(
                    Math.min(from.xCoord, to.xCoord),
                    Math.min(from.yCoord, to.yCoord),
                    Math.min(from.zCoord, to.zCoord),
                    Math.max(from.xCoord, to.xCoord),
                    Math.max(from.yCoord, to.yCoord),
                    Math.max(from.zCoord, to.zCoord))
                .expand(
                    MaidCombatTuning.MAX_SINGLE_UNIT,
                    MaidCombatTuning.MAX_SINGLE_UNIT,
                    MaidCombatTuning.MAX_SINGLE_UNIT);
            for (Object value : maid.worldObj.getEntitiesWithinAABBExcludingEntity(maid, bounds)) {
                if (!(value instanceof Entity entity) || entity == target
                    || entity == arrow
                    || !entity.canBeCollidedWith()) continue;
                var collision = entity.boundingBox.expand(
                    MaidCombatTuning.BOW_ATTACK_MIN_X,
                    MaidCombatTuning.BOW_ATTACK_MIN_X,
                    MaidCombatTuning.BOW_ATTACK_MIN_X);
                if (collision.isVecInside(from) || collision.calculateIntercept(from, to) != null) {
                    result.blocker = entity;
                    return Status.BLOCKED;
                }
            }
            state = physics.step(state, sample);
        }
        return null;
    }

    private boolean reject(Status reason) {
        rejectedShots++;
        retryAt = tick + MaidCombatTuning.BOW_ATTACK_RETRY_AT;
        interrupt();
        status = reason;
        return false;
    }

    /** 无出生/扣料/改拉弓计数的当前世界预检；候选位置不被写回射手或目标 */
    public Status preview(EntityLivingBase target, BallisticVector origin, BooleanSupplier admitted) {
        if (firing || !admitted.getAsBoolean()
            || target == null
            || !hasBow(maid)
            || !CompatServices.projectilePhysics()
                .arrowGravityAvailable()
            || !MaidProjectileImpactProtection.available(maid)) return Status.PHYSICS_UNAVAILABLE;
        var world = maid.worldObj;
        var provider = world.provider;
        var bow = maid.getHeldItem();
        var expected = bow.copy();
        var owner = maid.func_152113_b();
        var identity = maid.getUniqueID();
        var targetIdentity = target.getUniqueID();
        double x = maid.posX, y = maid.posY, z = maid.posZ;
        int dimension = maid.dimension;
        EntityArrow arrow = null;
        firing = true;
        try {
            var candidate = new EntityArrow(world, origin.x, origin.y, origin.z);
            arrow = candidate;
            candidate.shootingEntity = maid;
            BooleanSupplier valid = () -> admitted.getAsBoolean() && maid.worldObj == world
                && world.provider == provider
                && maid.dimension == dimension
                && identity.equals(maid.getUniqueID())
                && Objects.equals(owner, maid.func_152113_b())
                && world.getEntityByID(maid.getEntityId()) == maid
                && maid.posX == x
                && maid.posY == y
                && maid.posZ == z
                && maid.getHeldItem() == bow
                && ItemStack.areItemStacksEqual(bow, expected)
                && target.worldObj == world
                && target.dimension == dimension
                && target.isEntityAlive()
                && !target.isDead
                && targetIdentity.equals(target.getUniqueID())
                && world.getEntityByID(target.getEntityId()) == target;
            var launch = CompatServices.projectilePhysics()
                .sampleArrowLaunch(candidate);
            if (launch == null || !valid.getAsBoolean()) return Status.PHYSICS_UNAVAILABLE;
            return nativeAim(
                candidate,
                target,
                launch,
                () -> valid.getAsBoolean() && launch.isCurrent(candidate),
                false).status;
        } finally {
            if (arrow != null) arrow.setDead();
            firing = false;
        }
    }

    private NativeAim nativeAim(EntityArrow arrow, EntityLivingBase target,
        ProjectilePhysicsProvider.ArrowLaunchSample launch, BooleanSupplier current, boolean prototype) {
        var result = new NativeAim();
        if (!IntegrationGuard.available(Side.SERVER, BallisticsIds.GUARD)) return result;
        try {
            result.medium = new NativeArrowMedium(arrow, current);
            result.physics = ArrowPhysics.OVERWORLD_AIR.withGravity(launch.gravity())
                .withMedium(result.medium::at);
            result.targetPoint = aimPoint(target);
            var velocity = observed == target
                ? prototype || modernFlightProfile == ModernFlightProfile.NATIVE_MOTION_COMPENSATION ? motion.velocity()
                    : modernFlightProfile == ModernFlightProfile.NATIVE_LAST_SAMPLE_COMPENSATION
                        ? motion.latestVelocity()
                        : BallisticVector.ZERO
                : BallisticVector.ZERO;
            result.solution = ArrowInterceptSolver.solve(
                new BallisticVector(arrow.posX, arrow.posY, arrow.posZ),
                result.targetPoint,
                velocity,
                MaidCombatTuning.BOW_ATTACK_SPEND_SECOND_PARAM,
                MaidCombatTuning.MAX_FLIGHT_TICKS,
                result.physics);
            if (!result.solution.isSolved()) {
                result.status = Status.NO_SOLUTION;
                return result;
            }
            var initial = result.solution.initialVelocity;
            arrow.setThrowableHeading(
                initial.x,
                initial.y,
                initial.z,
                MaidCombatTuning.BOW_ATTACK_SET_THROWABLE_HEADING_FOURTH_FACTOR,
                MaidCombatTuning.BOW_ATTACK_SET_THROWABLE_HEADING_FIFTH_FACTOR);
            var safety = safePath(arrow, target, result.solution.flightTicks, result.physics, result.medium, result);
            result.status = safety == null && result.medium.isCurrent() ? Status.READY
                : safety == null ? Status.PHYSICS_UNAVAILABLE : safety;
        } catch (NativeArrowMedium.Unavailable failure) {
            result.status = failure.reason;
        } catch (Exception | LinkageError | AssertionError failure) {
            mediumFault(failure);
        }
        return result;
    }

    private boolean stillSafe(NativeAim result, EntityArrow arrow, EntityLivingBase target) {
        try {
            return aimPoint(target).subtract(result.targetPoint)
                .length() == 0 && result.medium.isCurrent()
                && safePath(arrow, target, result.solution.flightTicks, result.physics, result.medium, result) == null
                && result.medium.isCurrent();
        } catch (NativeArrowMedium.Unavailable failure) {
            return false;
        } catch (Exception | LinkageError | AssertionError failure) {
            mediumFault(failure);
            return false;
        }
    }

    private void mediumFault(Throwable failure) {
        IntegrationGuard.throwIfFatal(failure);
        var owner = maid.getOwner();
        IntegrationGuard.report(Side.SERVER, BallisticsIds.GUARD, failure, owner == null ? null : owner.getUniqueID());
    }

    public Refusal lastRefusal() {
        return lastRefusal;
    }

    private static final class NativeAim {

        Status status = Status.PHYSICS_UNAVAILABLE;
        ArrowInterceptSolver.Solution solution;
        ArrowPhysics physics;
        NativeArrowMedium medium;
        BallisticVector targetPoint;
        Entity blocker;
    }

    /** 仅本次确认的拒发事实；不会把失效、卸载、预算耗尽或出生取消变成目标失败 */
    public static final class Refusal {

        public final Status reason;
        public final long at;
        private final EntityMaid maid;
        private final EntityLivingBase target;
        private final World world;
        private final WorldProvider provider;
        private final UUID maidId, targetId;
        private final String owner;
        private final ItemStack bow, bowBefore, ammunition, ammoBefore;
        private final int slot, dimension;
        private final double x, y, z;
        private final NativeAim aim;
        private final double[] blockerBounds;

        private Refusal(EntityMaid maid, EntityLivingBase target, ItemStack bow, int slot, NativeAim aim) {
            this.maid = maid;
            this.target = target;
            this.bow = bow;
            this.slot = slot;
            this.aim = aim;
            world = maid.worldObj;
            provider = world.provider;
            dimension = maid.dimension;
            owner = maid.func_152113_b();
            maidId = maid.getUniqueID();
            targetId = target.getUniqueID();
            x = target.posX;
            y = target.posY;
            z = target.posZ;
            bowBefore = bow.copy();
            ammunition = maid.getMaidInventory()
                .getStackInSlot(slot);
            ammoBefore = ItemStack.copyItemStack(ammunition);
            reason = aim.status;
            at = world.getTotalWorldTime();
            blockerBounds = aim.blocker == null ? null : bounds(aim.blocker);
        }

        public boolean matches(EntityMaid actual, EntityLivingBase candidate) {
            return actual == maid && candidate == target
                && maid.worldObj == world
                && world.provider == provider
                && maid.dimension == dimension
                && provider.dimensionId == dimension
                && maidId.equals(maid.getUniqueID())
                && targetId.equals(target.getUniqueID())
                && Objects.equals(owner, maid.func_152113_b())
                && !maid.isDead
                && maid.isEntityAlive()
                && target.worldObj == world
                && target.dimension == dimension
                && target.isEntityAlive()
                && !target.isDead
                && world.getEntityByID(maid.getEntityId()) == maid
                && world.getEntityByID(target.getEntityId()) == target
                && target.posX == x
                && target.posY == y
                && target.posZ == z
                && maid.getHeldItem() == bow
                && ItemStack.areItemStacksEqual(bow, bowBefore)
                && maid.getMaidInventory()
                    .getStackInSlot(slot) == ammunition
                && ItemStack.areItemStacksEqual(ammunition, ammoBefore);
        }

        public boolean environmentUnchanged() {
            return aim.medium != null && aim.medium.environmentUnchanged()
                && (aim.blocker == null || !aim.blocker.isDead && aim.blocker.worldObj == world
                    && world.getEntityByID(aim.blocker.getEntityId()) == aim.blocker
                    && Arrays.equals(blockerBounds, bounds(aim.blocker)));
        }

        private static double[] bounds(Entity entity) {
            return new double[] { entity.boundingBox.minX, entity.boundingBox.minY, entity.boundingBox.minZ,
                entity.boundingBox.maxX, entity.boundingBox.maxY, entity.boundingBox.maxZ };
        }
    }

    private boolean canFire(EntityLivingBase target) {
        return !maid.worldObj.isRemote && MaidProjectileImpactProtection.available(maid)
            && maid.getCombatModeTask() == MaidTask.RANGED_ATTACK
            && !maid.getBrainCoordinator()
                .isSuspended()
            && !maid.isSitting()
            && maid.ridingEntity == null
            && !maid.getLeashed()
            && !maid.hasPose(MaidPose.SLEEPING)
            && MaidCombatTargeting.canAttack(maid, maid.getOwner(), target)
            && maid.getDistanceSqToEntity(target) <= MaidCombatTuning.RANGE_SQ
            && maid.getEntitySenses()
                .canSee(target);
    }

    private boolean canHoldBowUse() {
        return maid.worldObj != null && !maid.worldObj.isRemote
            && maid.isEntityAlive()
            && (!maid.isUnifiedCombat() || !maid.getCombatHitReceipt()
                .blocksTransfer())
            && maid.getCombatModeTask() == MaidTask.RANGED_ATTACK
            && maid.getBrainCoordinator()
                .allowsReactiveItemUse()
            && !maid.getBrainCoordinator()
                .isSuspended()
            && !maid.isSitting()
            && maid.ridingEntity == null
            && !maid.getLeashed()
            && !maid.hasPose(MaidPose.SLEEPING)
            && hasBow(maid);
    }

    public void interrupt() {
        generation++;
        drawTicks = MaidCombatTuning.MAX_INITIAL_VALUE;
        drawingBow = null;
        maid.getItemUseState()
            .release(useLease);
        useLease = null;
        status = Status.IDLE;
    }

    public void cancel() {
        interrupt();
        motion.clear();
        observed = null;
    }

    public Status getStatus() {
        return status;
    }

    public long getShots() {
        return shots;
    }

    public long getRejectedShots() {
        return rejectedShots;
    }

    public int getDrawTicks() {
        return drawTicks;
    }

    public ArrowInterceptSolver.Solution getLastSolution() {
        return lastSolution;
    }

    /** drag/gravity保留最近输入；空间查询持有发射许可，提交/撤销后不能用作活世界观察器 */
    public ArrowPhysics getLastPhysics() {
        return lastPhysics;
    }

    public EntityArrow getLastArrow() {
        return lastArrow;
    }

    /** 原availableInv(true)按主手、副手、背包顺序查找基础箭 */
    public static int modernArrowSlot(EntityMaid maid) {
        var inventory = maid.getMaidInventory();
        for (int slot : new int[] { MaidInventoryLayout.MAIN_HAND, MaidInventoryLayout.OFF_HAND }) {
            var stack = inventory.getStackInSlot(slot);
            if (stack != null && stack.stackSize > 0 && stack.getItem() == Items.arrow) return slot;
        }
        for (int slot = 0; slot < MaidInventoryLayout.BACKPACK_SIZE; slot++) {
            var stack = inventory.getStackInSlot(slot);
            if (stack != null && stack.stackSize > 0 && stack.getItem() == Items.arrow) return slot;
        }
        return MaidCombatTuning.MISSING_AMMUNITION_SLOT;
    }

    private int arrowSlot() {
        var inventory = maid.getMaidInventory();
        for (int slot = 0; slot <= MaidInventoryLayout.OFF_HAND; slot++) {
            var stack = inventory.getStackInSlot(slot);
            if (stack != null && stack.stackSize > 0 && stack.getItem() == Items.arrow) return slot;
        }
        return MaidCombatTuning.MISSING_AMMUNITION_SLOT;
    }

    private static BallisticVector aimPoint(EntityLivingBase target) {
        return new BallisticVector(
            target.posX,
            target.boundingBox.minY + target.height * MaidCombatTuning.BOW_ATTACK_MIN_Y,
            target.posZ);
    }

    private static Vec3 vec(BallisticVector point) {
        return Vec3.createVectorHelper(point.x, point.y, point.z);
    }
}
