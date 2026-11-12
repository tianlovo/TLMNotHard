package com.tianluoqaq.tlmnh.compat.galacticraftcore.common.space;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** 女仆供氧已由生存控制器结算，拦截 GC 对普通非玩家生物的第二次缺氧结算 */
public final class GalacticraftMaidOxygenEvents {

    private static final GalacticraftMaidOxygenEvents INSTANCE = new GalacticraftMaidOxygenEvents();
    private static boolean registered;

    private GalacticraftMaidOxygenEvents() {}

    public static synchronized void register() {
        if (registered) return;
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        registered = true;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onSuffocation(LivingEvent event) {
        if (!event.getClass()
            .getName()
            .equals(GalacticraftSpaceReflectionSpec.SUFFOCATION_PRE_CLASS)
            || !(event.entityLiving instanceof EntityMaid maid)
            || GalacticraftSpaceSurvivalCompat.isControllerPre(maid)
            || maid.worldObj == null
            || maid.worldObj.isRemote
            || !maid.isEntityAlive()
            || maid.worldObj.getEntityByID(maid.getEntityId()) != maid) return;
        var active = CompatServices.spaceSurvival();
        if (active == GalacticraftSpaceSurvivalCompat.INSTANCE && active.isAvailable()
            && active.sampleAtmosphere(maid.worldObj).galacticraftDimension) event.setCanceled(true);
    }
}
