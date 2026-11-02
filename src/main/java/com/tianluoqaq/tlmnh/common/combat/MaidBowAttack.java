package com.tianluoqaq.tlmnh.common.combat;

import java.util.function.BooleanSupplier;

import net.minecraft.block.material.Material;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.MaidCombatTargeting;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.WorkComponent;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Hand;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Kind;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Lease;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.compat.galacticraftcore.common.physics.NativeArrowGravity;

/** 原生弓箭执行器：观察、拉弓、真实弹道安全检查和成功生成后的资源结算 */
public final class MaidBowAttack {

    public static final int DRAW_TICKS = 20;
    public static final int MAX_FLIGHT_TICKS = 60;
    public static final double RANGE_SQ = 144;

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
        SPAWN_REJECTED
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
    private boolean firing;
    private Lease useLease;
    private long generation;
    private ModernFlightProfile modernFlightProfile = ModernFlightProfile.NATIVE_STATIC_COMPENSATION;

    public MaidBowAttack(EntityMaid maid) {
        this(maid, ModernFlightProfile.NATIVE_STATIC_COMPENSATION);
    }

    public MaidBowAttack(EntityMaid maid, ModernFlightProfile profile) {
        this.maid = java.util.Objects.requireNonNull(maid, "maid");
        modernFlightProfile = java.util.Objects.requireNonNull(profile, "profile");
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
            drawTicks = 0;
            status = Status.BLOCKED;
            return false;
        }
        drawTicks++;
        status = drawTicks >= DRAW_TICKS ? Status.READY : Status.DRAWING;
        return drawTicks >= DRAW_TICKS;
    }

    public boolean fire(EntityLivingBase target) {
        if (firing) return false;
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
        if (!NativeArrowGravity.available()) return false;
        if (!canFire(target) || target != observed
            || drawTicks < DRAW_TICKS
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
            1F);
    }

    /** 原行为已stopUsingItem后调用，不要求原型draw20/MAIN租约/12格/禁止骑乘等准入 */
    public boolean fireForModern(EntityLivingBase target, float charge, BooleanSupplier admitted) {
        if (!NativeArrowGravity.available() || !MaidProjectileImpactProtection.available(maid)) return false;
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
        if (!Boolean.getBoolean("tlmnh.devCommands") || firing)
            throw new IllegalStateException("Development flight profile cannot change in this context");
        interrupt();
        modernFlightProfile = java.util.Objects.requireNonNull(profile, "profile");
    }

    private boolean release(EntityLivingBase target, ItemStack bow, int slot, BooleanSupplier admitted,
        boolean prototype, float charge) {
        if (bow == null || slot < 0 || !admitted.getAsBoolean()) return false;
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
        var origin = new BallisticVector(launchX, launchY + launchEyeHeight - (original ? (double) .1F : .1), launchZ);
        var telemetry = maid.getBrainCoordinator()
            .getTelemetry();
        boolean recording = telemetry.isEnabled();
        EntityArrow arrow;
        NativeArrowGravity.LaunchSample launch;
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
            launch = NativeArrowGravity.sampleForLaunch(arrow);
        } else {
            // 查询必须针对真正将要生成的箭，不用代理箭或主世界固定值代替实际provider
            arrow = new EntityArrow(world, origin.x, origin.y, origin.z);
            launch = NativeArrowGravity.sampleForLaunch(arrow);
            if (launch == null || !launch.isCurrent(arrow) || !current.getAsBoolean()) {
                arrow.setDead();
                return false;
            }
            var physics = ArrowPhysics.OVERWORLD_AIR.withGravity(launch.gravity);
            lastPhysics = physics;
            long startedAt = recording ? System.nanoTime() : 0;
            try {
                lastSolution = ArrowInterceptSolver.solve(
                    origin,
                    aimPoint(target),
                    prototype || modernFlightProfile == ModernFlightProfile.NATIVE_MOTION_COMPENSATION
                        ? motion.velocity()
                        : modernFlightProfile == ModernFlightProfile.NATIVE_LAST_SAMPLE_COMPENSATION
                            ? motion.latestVelocity()
                            : BallisticVector.ZERO,
                    3.0,
                    MAX_FLIGHT_TICKS,
                    physics);
            } finally {
                if (recording) telemetry.work("bow_solver", WorkComponent.SOLVER, System.nanoTime() - startedAt);
            }
            if (!lastSolution.isSolved()) {
                arrow.setDead();
                return reject(Status.NO_SOLUTION);
            }
            // 使用原生定向接口，零随机散布使预瞄误差可归因；保留其实际浮点归一化结果
            var velocity = lastSolution.initialVelocity;
            arrow.setThrowableHeading(velocity.x, velocity.y, velocity.z, 3.0F, 0.0F);
            var safety = safePath(arrow, target, lastSolution.flightTicks, physics);
            if (safety != null) {
                arrow.setDead();
                return reject(safety);
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
        arrow.canBePickedUp = infinite ? original ? 0 : 2 : 1;
        arrow.setIsCritical(prototype);
        int power = EnchantmentHelper.getEnchantmentLevel(Enchantment.power.effectId, bow);
        if (power > 0) arrow.setDamage(arrow.getDamage() + power * 0.5 + 0.5);
        int punch = EnchantmentHelper.getEnchantmentLevel(Enchantment.punch.effectId, bow);
        if (punch > 0) arrow.setKnockbackStrength(punch);
        if (EnchantmentHelper.getEnchantmentLevel(Enchantment.flame.effectId, bow) > 0) arrow.setFire(100);
        if (!prototype) arrow.setDamage(
            arrow.getDamage() * (float) (maid.getEntityAttribute(SharedMonsterAttributes.attackDamage)
                .getBaseValue() / 2.0));
        var arrowBefore = new NBTTagCompound();
        arrow.writeToNBT(arrowBefore);
        var boundsBefore = new double[] { arrow.boundingBox.minX, arrow.boundingBox.minY, arrow.boundingBox.minZ,
            arrow.boundingBox.maxX, arrow.boundingBox.maxY, arrow.boundingBox.maxZ };
        if (!world.spawnEntityInWorld(arrow)) {
            arrow.setDead();
            return reject(Status.SPAWN_REJECTED);
        }
        // spawn事件允许重入；若事件改了库存，撤销箭实体，不能扣另一组资源或免费射击
        var arrowAfter = new NBTTagCompound();
        arrow.writeToNBT(arrowAfter);
        var boundsAfter = new double[] { arrow.boundingBox.minX, arrow.boundingBox.minY, arrow.boundingBox.minZ,
            arrow.boundingBox.maxX, arrow.boundingBox.maxY, arrow.boundingBox.maxZ };
        if (arrow.isDead || arrow.shootingEntity != maid
            || !arrowBefore.equals(arrowAfter)
            || !java.util.Arrays.equals(boundsBefore, boundsAfter)
            || !launch.isCurrent(arrow)
            || !current.getAsBoolean()) {
            arrow.setDead();
            return reject(Status.SPAWN_REJECTED);
        }
        if (!infinite) maid.getMaidInventory()
            .decrStackSize(slot, 1);
        bow.damageItem(1, maid);
        if (bow.stackSize <= 0) maid.setCurrentItemOrArmor(0, null);
        maid.worldObj.playSoundAtEntity(
            maid,
            "random.bow",
            1,
            original ? 1F / (maid.getRNG()
                .nextFloat() * .4F + .8F) : 1F);
        shots++;
        lastArrow = arrow;
        telemetry.releaseConfirmed(arrow, target);
        interrupt();
        status = Status.FIRED;
        return true;
    }

    private Status safePath(EntityArrow arrow, EntityLivingBase target, double flightTicks, ArrowPhysics physics) {
        var state = new ArrowPhysics.State(
            new BallisticVector(arrow.posX, arrow.posY, arrow.posZ),
            new BallisticVector(arrow.motionX, arrow.motionY, arrow.motionZ));
        for (int step = 0; step < Math.ceil(flightTicks); step++) {
            var end = state.position.add(state.velocity.scale(Math.min(1, flightTicks - step)));
            int minX = MathHelper.floor_double(Math.min(state.position.x, end.x) - .3);
            int maxX = MathHelper.floor_double(Math.max(state.position.x, end.x) + .3);
            int minY = MathHelper.floor_double(Math.min(state.position.y, end.y) - .5);
            int maxY = MathHelper.floor_double(Math.max(state.position.y, end.y) + 1);
            int minZ = MathHelper.floor_double(Math.min(state.position.z, end.z) - .3);
            int maxZ = MathHelper.floor_double(Math.max(state.position.z, end.z) + .3);
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                if (!maid.worldObj.getChunkProvider()
                    .chunkExists(x >> 4, z >> 4)) return Status.UNLOADED;
                for (int y = minY; y <= maxY; y++) {
                    if (!maid.worldObj.blockExists(x, y, z)) return Status.UNLOADED;
                    Material material = maid.worldObj.getBlock(x, y, z)
                        .getMaterial();
                    if (material.isLiquid()) return Status.LIQUID;
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
                .expand(1, 1, 1);
            for (Object value : maid.worldObj.getEntitiesWithinAABBExcludingEntity(maid, bounds)) {
                if (!(value instanceof Entity entity) || entity == target
                    || entity == arrow
                    || !entity.canBeCollidedWith()) continue;
                var collision = entity.boundingBox.expand(.3, .3, .3);
                if (collision.isVecInside(from) || collision.calculateIntercept(from, to) != null)
                    return Status.BLOCKED;
            }
            state = physics.step(state);
        }
        return null;
    }

    private boolean reject(Status reason) {
        rejectedShots++;
        retryAt = tick + 20;
        interrupt();
        status = reason;
        return false;
    }

    private boolean canFire(EntityLivingBase target) {
        return !maid.worldObj.isRemote && MaidProjectileImpactProtection.available(maid)
            && maid.getTask() == MaidTask.RANGED_ATTACK
            && !maid.getBrainCoordinator()
                .isSuspended()
            && !maid.isSitting()
            && maid.ridingEntity == null
            && !maid.getLeashed()
            && !maid.hasPose(MaidPose.SLEEPING)
            && MaidCombatTargeting.canAttack(maid, maid.getOwner(), target)
            && maid.getDistanceSqToEntity(target) <= RANGE_SQ
            && maid.getEntitySenses()
                .canSee(target);
    }

    private boolean canHoldBowUse() {
        return maid.worldObj != null && !maid.worldObj.isRemote
            && maid.isEntityAlive()
            && maid.getTask() == MaidTask.RANGED_ATTACK
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
        drawTicks = 0;
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

    public ArrowPhysics getLastPhysics() {
        return lastPhysics;
    }

    public EntityArrow getLastArrow() {
        return lastArrow;
    }

    /** 原availableInv(true)按主手、副手、背包顺序查找基础箭 */
    public static int modernArrowSlot(EntityMaid maid) {
        var inventory = maid.getMaidInventory();
        int[] slots = { MaidInventory.MAIN_HAND, MaidInventory.OFF_HAND, 0, 1, 2, 3, 4, 5 };
        for (int slot : slots) {
            var stack = inventory.getStackInSlot(slot);
            if (stack != null && stack.stackSize > 0 && stack.getItem() == Items.arrow) return slot;
        }
        return -1;
    }

    private int arrowSlot() {
        var inventory = maid.getMaidInventory();
        for (int slot = 0; slot <= MaidInventory.OFF_HAND; slot++) {
            var stack = inventory.getStackInSlot(slot);
            if (stack != null && stack.stackSize > 0 && stack.getItem() == Items.arrow) return slot;
        }
        return -1;
    }

    private static BallisticVector aimPoint(EntityLivingBase target) {
        return new BallisticVector(target.posX, target.boundingBox.minY + target.height * .5, target.posZ);
    }

    private static Vec3 vec(BallisticVector point) {
        return Vec3.createVectorHelper(point.x, point.y, point.z);
    }
}
