/*
 * Adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/favorability/{FavorabilityManager,Type}.java
 * Copyright (c) TartaricAcid contributors, MIT; see licenses/TouhouLittleMaid-MIT.txt
 * RC2 adaptation: native attributes/NBT/status particles; no baubles or excluded activities
 */
package com.github.tartaricacid.touhoulittlemaid.entity.favorability;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.common.MinecraftForge;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidFavorabilityLevelChangeEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

public class FavorabilityManager {

    public static final Map<String, Type> TYPES = new HashMap<>();
    private static final String TAG_NAME = "FavorabilityManagerCounter";
    private static final int[] POINTS = { 0, 64, 192, 384 };
    private static final int[] HEALTH = { 20, 30, 40, 80 };
    private static final int[] ATTACK = { 2, 3, 4, 6 };
    private static final int[] REACH = { 0, 1, 3, 5 };
    private final Map<String, Integer> counter = new HashMap<>();
    private final EntityMaid maid;

    public FavorabilityManager(EntityMaid maid) {
        this.maid = maid;
    }

    public void tick() {
        counter.replaceAll((name, time) -> Math.max(0, time - 1));
    }

    public void apply(String type) {
        Type value = TYPES.get(type);
        if (value != null) apply(value);
    }

    public void apply(Type type) {
        apply(type, type.getPoint());
    }

    public void apply(Type type, int point) {
        if (!canAdd(type.getTypeName())) return;
        // 先占冷却，避免等级事件中的再次调用重复发奖励
        counter.put(type.getTypeName(), Math.max(0, type.getCooldown()));
        if (type.isReduce()) reduce(point);
        else add(point);
    }

    public boolean canAdd(String type) {
        return counter.getOrDefault(type, 0) <= 0;
    }

    public int getLevel() {
        return level(maid.getFavorability());
    }

    private static int level(int points) {
        return points >= 384 ? 3 : points >= 192 ? 2 : points >= 64 ? 1 : 0;
    }

    public int getPointByLevel(int level) {
        return POINTS[boundedLevel(level)];
    }

    public int getHealthByLevel(int level) {
        return HEALTH[boundedLevel(level)];
    }

    public int getAttackByLevel(int level) {
        return ATTACK[boundedLevel(level)];
    }

    public int getAttackDistancePlusByPoint(int favorability) {
        return REACH[level(favorability)];
    }

    public AxisAlignedBB getSweepRange(Entity target, int favorability) {
        double range = level(favorability) + 1;
        return target.boundingBox.expand(range, Math.max(range / 4, .25), range);
    }

    private static int boundedLevel(int level) {
        return Math.max(0, Math.min(3, level));
    }

    public double getLevelPercent() {
        int level = getLevel();
        return level == 3 ? 0 : (maid.getFavorability() - POINTS[level]) / (double) (POINTS[level + 1] - POINTS[level]);
    }

    public int nextLevelPoint() {
        return getLevel() == 3 ? 0 : POINTS[getLevel() + 1] - maid.getFavorability();
    }

    public void add(int point) {
        int before = getLevel();
        maid.setFavorability((int) Math.max(0, Math.min(384L, (long) maid.getFavorability() + Math.max(0, point))));
        if (before != getLevel()) {
            refreshAttributes();
            onFavorabilityLevelChange(before, getLevel());
        }
        if (!maid.worldObj.isRemote) maid.worldObj.setEntityState(maid, (byte) 7);
    }

    public void reduce(int point) {
        maid.setFavorability((int) Math.max(POINTS[getLevel()], (long) maid.getFavorability() - Math.max(0, point)));
    }

    public void reduceWithoutLevel(int point) {
        int before = getLevel();
        maid.setFavorability((int) Math.max(0, (long) maid.getFavorability() - Math.max(0, point)));
        if (before != getLevel()) {
            refreshAttributes();
            onFavorabilityLevelChange(before, getLevel());
        }
    }

    public void max() {
        add(384);
    }

    /** 更新自有基础值，原版装备与其他修饰符仍由属性系统合并 */
    public void refreshAttributes() {
        maid.getEntityAttribute(SharedMonsterAttributes.attackDamage)
            .setBaseValue(getAttackByLevel(getLevel()));
        maid.getEntityAttribute(SharedMonsterAttributes.maxHealth)
            .setBaseValue(getHealthByLevel(getLevel()) + (maid.isStruckByLightning() ? 20 : 0));
        if (maid.getHealth() > maid.getMaxHealth()) maid.setHealth(maid.getMaxHealth());
    }

    public void onFavorabilityLevelChange(int oldLevel, int newLevel) {
        MinecraftForge.EVENT_BUS.post(new MaidFavorabilityLevelChangeEvent(maid, oldLevel, newLevel));
    }

    public void addAdditionalSaveData(NBTTagCompound tag) {
        var data = new NBTTagCompound();
        counter.forEach(data::setInteger);
        tag.setTag(TAG_NAME, data);
        tag.removeTag("TLMNHWorkMealCooldown");
    }

    public void readAdditionalSaveData(NBTTagCompound tag) {
        counter.clear();
        var data = tag.getCompoundTag(TAG_NAME);
        for (Object key : data.func_150296_c()) {
            String name = (String) key;
            counter.put(name, Math.max(0, data.getInteger(name)));
        }
        if (!counter.containsKey(Type.WORK_MEAL.getTypeName()) && tag.hasKey("TLMNHWorkMealCooldown")) counter
            .put(Type.WORK_MEAL.getTypeName(), Math.max(0, Math.min(3600, tag.getInteger("TLMNHWorkMealCooldown"))));
        long rewardAt = maid.getEntityData()
            .getLong("TLMNHStealEdibleRewardAt");
        long now = maid.worldObj.getTotalWorldTime();
        if (!counter.containsKey(Type.STEAL_EDIBLE_BLOCK.getTypeName()) && rewardAt > now) {
            long delay = rewardAt - now;
            counter.put(Type.STEAL_EDIBLE_BLOCK.getTypeName(), delay < 0 ? 3600 : (int) Math.min(3600, delay));
        }
        maid.getEntityData()
            .removeTag("TLMNHStealEdibleRewardAt");
    }
}
