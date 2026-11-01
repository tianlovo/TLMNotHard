package com.tianluoqaq.tlmnh.common.ai;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.config.MaidNavigationConfig;

/** 所有生产导航共享世界额度，原版对照适配器保留显式无预算路径 */
public final class MaidWorldNavigationBudget {

    private static final Map<World, MaidNavigationBudget> WORLDS = new WeakHashMap<>();

    private MaidWorldNavigationBudget() {}

    public static MaidNavigationBudget forWorld(World world) {
        if (world == null || world.isRemote) throw new IllegalArgumentException("Server world required");
        return WORLDS.computeIfAbsent(world, ignored -> new MaidNavigationBudget(MaidNavigationConfig.queriesPerTick));
    }

    public static boolean acquire(EntityMaid maid) {
        return forWorld(maid.worldObj).acquire(maid.getUniqueID(), maid.worldObj.getTotalWorldTime());
    }

    public static void cancel(EntityMaid maid) {
        var budget = WORLDS.get(maid.worldObj);
        if (budget != null) budget.cancel(maid.getUniqueID());
    }

    public static void unload(World world) {
        WORLDS.remove(world);
    }
}
