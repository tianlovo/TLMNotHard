package com.tianluoqaq.tlmnh.common.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.TLMNotHard;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Forge1614的真实区块/世界边界，不让一个女仆的清理失败阻断其他实体或原生卸载 */
public final class MaidBrainLifecycle {

    private static final Set<World> UNLOADING_WORLDS = Collections.newSetFromMap(new WeakHashMap<World, Boolean>());
    private static boolean registered;

    public static void register() {
        if (registered) throw new IllegalStateException("Maid brain lifecycle already registered");
        registered = true;
        MinecraftForge.EVENT_BUS.register(new MaidBrainLifecycle());
    }

    public static boolean isWorldUnloading(World world) {
        return world != null && !world.isRemote && UNLOADING_WORLDS.contains(world);
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (event.world.isRemote) return;
        var visited = Collections.newSetFromMap(new IdentityHashMap<EntityMaid, Boolean>());
        for (var entities : event.getChunk().entityLists) visit(
            new ArrayList<>(entities),
            visited,
            "chunk_unload",
            maid -> maid.getBrainCoordinator()
                .onUnload());
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (event.world.isRemote || isWorldUnloading(event.world)) return;
        var visited = Collections.newSetFromMap(new IdentityHashMap<EntityMaid, Boolean>());
        for (var entities : event.getChunk().entityLists) visit(
            new ArrayList<>(entities),
            visited,
            "chunk_load",
            maid -> maid.getBrainCoordinator()
                .onRegistered());
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world.isRemote) return;
        MaidWorldNavigationBudget.unload(event.world);
        com.tianluoqaq.tlmnh.common.ai.perception.MaidWorldSensingBudget.unload(event.world);
        UNLOADING_WORLDS.add(event.world);
        visit(
            new ArrayList<>(event.world.loadedEntityList),
            Collections.newSetFromMap(new IdentityHashMap<EntityMaid, Boolean>()),
            "world_unload",
            maid -> maid.getBrainCoordinator()
                .onUnload());
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (!event.world.isRemote) UNLOADING_WORLDS.remove(event.world);
    }

    private static void visit(Iterable<?> entities, Set<EntityMaid> visited, String operation,
        Consumer<EntityMaid> action) {
        for (Object value : entities) {
            if (!(value instanceof EntityMaid maid) || !visited.add(maid)) continue;
            try {
                action.accept(maid);
            } catch (RuntimeException | LinkageError | AssertionError failure) {
                TLMNotHard.LOG.error(
                    "Maid policy lifecycle failed; actor remains blocked: operation={} maid={}",
                    operation,
                    maid.getUniqueID(),
                    failure);
            }
        }
    }
}
