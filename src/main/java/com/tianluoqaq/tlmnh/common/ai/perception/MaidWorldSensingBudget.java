package com.tianluoqaq.tlmnh.common.ai.perception;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.config.MaidPerceptionConfig;

/** 增强扫描共用一个世界配额，F保持原分频合同 */
public final class MaidWorldSensingBudget {

    private static final Map<World, MaidSensingBudget> WORLDS = new WeakHashMap<>();

    private MaidWorldSensingBudget() {}

    public static MaidSensingBudget forWorld(World world) {
        if (world == null || world.isRemote) throw new IllegalArgumentException("Server world required");
        return WORLDS.computeIfAbsent(world, ignored -> new MaidSensingBudget(MaidPerceptionConfig.scansPerTick));
    }

    public static void unload(World world) {
        var old = WORLDS.remove(world);
        if (old != null) old.clear();
    }
}
