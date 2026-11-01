package com.tianluoqaq.tlmnh.common.ai.faithful;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.entity.EntityLivingBase;

/** 原NearestVisibleLivingEntities的惰性结果缓存，正阴性均持续到下一次附近清单刷新 */
public final class MaidVisibleLivingEntities {

    private final List<EntityLivingBase> nearby;
    private final Map<EntityLivingBase, Boolean> cache = new IdentityHashMap<>();
    private final Predicate<EntityLivingBase> targetable;
    private final Runnable guard;

    MaidVisibleLivingEntities(List<EntityLivingBase> nearby, Predicate<EntityLivingBase> targetable, Runnable guard) {
        this.nearby = nearby;
        this.targetable = targetable;
        this.guard = guard;
    }

    public boolean contains(EntityLivingBase entity) {
        guard.run();
        return nearby.contains(entity) && visible(entity);
    }

    public EntityLivingBase findClosest(Predicate<EntityLivingBase> predicate) {
        guard.run();
        for (var entity : nearby) if (predicate.test(entity) && visible(entity)) return entity;
        return null;
    }

    public List<EntityLivingBase> findAll(Predicate<EntityLivingBase> predicate) {
        guard.run();
        var matches = new ArrayList<EntityLivingBase>();
        for (var entity : nearby) if (predicate.test(entity) && visible(entity)) matches.add(entity);
        return java.util.Collections.unmodifiableList(matches);
    }

    private boolean visible(EntityLivingBase entity) {
        guard.run();
        return cache.computeIfAbsent(entity, targetable::test);
    }
}
