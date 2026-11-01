package com.tianluoqaq.tlmnh.common.ai.faithful;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.BooleanSupplier;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemBow;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.DamageSource;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.sensor.MaidHostilesSensor;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.sensor.MaidNearestLivingEntitySensor;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.sensor.MaidPickupEntitiesSensor;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.config.MaidPerceptionConfig;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** 现代组件的附近/可见/危险/受伤/拾取记忆，不与原型危险缓存混用 */
public final class MaidModernPerception {

    private static final int SCAN_RATE = 20;
    // Sensor使用独立的线程安全随机源，不消耗世界/女仆Behavior的随机流
    private static final Random RANDOM = new Random();
    private final EntityMaid maid;
    private final BooleanSupplier current;
    private final MaidNearestLivingEntitySensor living = new MaidNearestLivingEntitySensor();
    private final MaidHurtBySensor hurt = new MaidHurtBySensor();
    private final MaidHostilesSensor hostile = new MaidHostilesSensor();
    private final MaidPickupEntitiesSensor pickup = new MaidPickupEntitiesSensor();
    private int livingDelay = RANDOM.nextInt(SCAN_RATE), hurtDelay = RANDOM.nextInt(SCAN_RATE),
        hostileDelay = RANDOM.nextInt(SCAN_RATE), pickupDelay = RANDOM.nextInt(30);
    private boolean active;
    private long epoch, scans, hostileScans, hurtScans, pickupScans;
    private List<EntityLivingBase> nearby = Collections.emptyList();
    private MaidVisibleLivingEntities visible;
    private EntityLivingBase nearestHostile, attackTarget;
    private DamageSource hurtBy;
    private EntityLivingBase hurtByEntity;
    private List<Entity> pickupEntities = Collections.emptyList();

    public MaidModernPerception(EntityMaid maid, BooleanSupplier current) {
        this.maid = Objects.requireNonNull(maid, "maid");
        this.current = Objects.requireNonNull(current, "current");
    }

    public void tick() {
        if (!current.getAsBoolean()) throw new IllegalStateException("Expired perception frame");
        active = true;
        if (--livingDelay <= 0) {
            livingDelay = SCAN_RATE;
            living.doTick(maid, this);
            scans++;
        }
        if (--hurtDelay <= 0) {
            hurtDelay = SCAN_RATE;
            hurt.doTick(maid, this);
            hurtScans++;
        }
        if (--hostileDelay <= 0) {
            hostileDelay = SCAN_RATE;
            hostile.doTick(maid, this);
            hostileScans++;
        }
        if (--pickupDelay <= 0) {
            pickupDelay = 30;
            pickup.doTick(maid, this);
            pickupScans++;
        }
    }

    public AxisAlignedBB searchDimension() {
        guard();
        var area = maid.getHomeArea();
        boolean bowTask = area.activity() == Activity.WORK && maid.getTask() == MaidTask.RANGED_ATTACK;
        double radius = bowTask ? MaidPerceptionConfig.bowRange : area.restrictionRadius();
        var held = maid.getHeldItem();
        double vertical = bowTask && held != null && held.getItem() instanceof ItemBow ? radius : 4;
        if (area.enabled()) {
            var p = area.restrictionCenter();
            return AxisAlignedBB.getBoundingBox(p.x, p.y, p.z, p.x + 1, p.y + 1, p.z + 1)
                .expand(radius, vertical, radius);
        }
        return maid.boundingBox.expand(radius, vertical, radius);
    }

    public void setNearby(List<EntityLivingBase> entities) {
        guard();
        nearby = Collections.unmodifiableList(new ArrayList<>(entities));
        long snapshotEpoch = ++epoch;
        visible = new MaidVisibleLivingEntities(nearby, this::targetable, () -> {
            guard();
            if (epoch != snapshotEpoch) throw new IllegalStateException("Retired visible entity memory");
        });
    }

    public AxisAlignedBB pickupSearchDimension() {
        guard();
        var area = maid.getHomeArea();
        if (area.enabled()) {
            var p = area.restrictionCenter();
            return AxisAlignedBB.getBoundingBox(p.x, p.y, p.z, p.x + 1, p.y + 1, p.z + 1)
                .expand(area.restrictionRadius(), 4, area.restrictionRadius());
        }
        return maid.boundingBox.expand(area.restrictionRadius(), 4, area.restrictionRadius());
    }

    public List<Entity> pickupEntities() {
        guard();
        return pickupEntities;
    }

    public void setPickupEntities(List<Entity> entities) {
        guard();
        pickupEntities = Collections.unmodifiableList(new ArrayList<>(entities));
    }

    public DamageSource hurtBy() {
        guard();
        return hurtBy;
    }

    public void setHurtBy(DamageSource source) {
        guard();
        hurtBy = source;
    }

    public EntityLivingBase hurtByEntity() {
        guard();
        return hurtByEntity;
    }

    public void setHurtByEntity(EntityLivingBase entity) {
        guard();
        hurtByEntity = entity;
    }

    public float taskSearchRadius() {
        guard();
        return maid.getTask() == MaidTask.RANGED_ATTACK ? MaidPerceptionConfig.bowRange
            : maid.getHomeArea()
                .restrictionRadius();
    }

    public List<EntityLivingBase> nearby() {
        guard();
        return nearby;
    }

    public MaidVisibleLivingEntities visible() {
        guard();
        return visible;
    }

    public EntityLivingBase nearestHostile() {
        guard();
        return nearestHostile;
    }

    public void setNearestHostile(EntityLivingBase entity) {
        guard();
        nearestHostile = entity;
    }

    /** ATTACK_TARGET身份仅用于原Sensor的忽略隐形分支，攻击行为仍须独立验证实际许可 */
    public void setAttackTarget(EntityLivingBase entity) {
        guard();
        attackTarget = entity;
    }

    private boolean targetable(EntityLivingBase entity) {
        if (entity == maid || !entity.isEntityAlive()
            || entity instanceof EntityPlayerMP player && player.theItemInWorldManager.getGameType()
                .getID() == 3)
            return false;
        double visibility = 1;
        if (entity != attackTarget) {
            if (entity.isSneaking()) visibility *= .8;
            if (entity.isInvisible()) {
                int armor = 0;
                for (int slot = 1; slot <= 4; slot++) {
                    var item = entity.getEquipmentInSlot(slot);
                    if (item != null && item.stackSize > 0) armor++;
                }
                visibility *= .7 * Math.max(.1F, armor / 4F);
            }
        }
        double range = Math.max(2, 16 * visibility);
        return maid.getDistanceSqToEntity(entity) <= range * range && maid.getEntitySenses()
            .canSee(entity);
    }

    public void clear() {
        active = false;
        epoch++;
        nearby = Collections.emptyList();
        visible = null;
        nearestHostile = null;
        attackTarget = null;
        hurtBy = null;
        hurtByEntity = null;
        pickupEntities = Collections.emptyList();
        livingDelay = RANDOM.nextInt(SCAN_RATE);
        hurtDelay = RANDOM.nextInt(SCAN_RATE);
        hostileDelay = RANDOM.nextInt(SCAN_RATE);
        pickupDelay = RANDOM.nextInt(30);
    }

    private void guard() {
        if (!active || !current.getAsBoolean()) throw new IllegalStateException("Expired perception memory");
    }

    public long livingScanCount() {
        return scans;
    }

    public long hostileScanCount() {
        return hostileScans;
    }

    public long hurtScanCount() {
        return hurtScans;
    }

    public long pickupScanCount() {
        return pickupScans;
    }
}
