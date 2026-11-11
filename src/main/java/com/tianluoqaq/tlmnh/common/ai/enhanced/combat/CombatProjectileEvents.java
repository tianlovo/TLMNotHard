package com.tianluoqaq.tlmnh.common.ai.enhanced.combat;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.entity.projectile.EntityArrow;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorldBudget;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** 既成箭出生事实的固定环；不读取隐藏位置，也不在事件中逐女仆广播 */
public final class CombatProjectileEvents {

    private static final Map<Object, Ring> WORLDS = new WeakHashMap<>();
    private static boolean registered;

    private CombatProjectileEvents() {}

    public static void register() {
        if (registered) return;
        registered = true;
        MinecraftForge.EVENT_BUS.register(new CombatProjectileEvents());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void joined(EntityJoinWorldEvent event) {
        if (event.world.isRemote || event.isCanceled() || !(event.entity instanceof EntityArrow arrow)) return;
        Ring ring = WORLDS.get(event.world);
        if (ring == null) {
            ring = new Ring();
            WORLDS.put(event.world, ring);
        }
        long sequence = ++ring.sequence;
        ring.entries[(int) (sequence % EnhancedCombatTuning.CAPACITY)] = new Entry(sequence, arrow);
    }

    @SubscribeEvent
    public void unloaded(WorldEvent.Unload event) {
        unload(event.world);
        CombatWorldBudget.unload(event.world);
    }

    /** 只退役本组件拥有的箭出生历史，调用者负责世界生命周期或已验证的独占空边界 */
    public static void unload(Object world) {
        WORLDS.remove(world);
    }

    public static long sequence(Object world) {
        Ring ring = WORLDS.get(world);
        return ring == null ? EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE : ring.sequence;
    }

    public static long oldest(long latest) {
        return Math
            .max(EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_UNIT_STEP, latest - EnhancedCombatTuning.CAPACITY + 1);
    }

    public static EntityArrow arrow(Object world, long sequence) {
        Ring ring = WORLDS.get(world);
        Entry entry = ring == null ? null : ring.entries[(int) (sequence % EnhancedCombatTuning.CAPACITY)];
        return entry != null && entry.sequence == sequence ? entry.arrow.get() : null;
    }

    private static final class Ring {

        long sequence;
        final Entry[] entries = new Entry[EnhancedCombatTuning.CAPACITY];
    }

    private static final class Entry {

        final long sequence;
        final WeakReference<EntityArrow> arrow;

        Entry(long sequence, EntityArrow arrow) {
            this.sequence = sequence;
            this.arrow = new WeakReference<>(arrow);
        }
    }
}
