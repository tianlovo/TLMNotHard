package com.tianluoqaq.tlmnh.common.lifecycle;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingSpawnEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** FML发放事件与Forge死亡/生成事件；不参与女仆行为决策 */
public final class MaidLifecycleEvents {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPendingUpdate(net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent event) {
        if (event.entityLiving instanceof EntityMaid maid && MaidLifecycleService.isPendingRedemption(maid))
            event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPendingInteraction(net.minecraftforge.event.entity.player.EntityInteractEvent event) {
        if (event.target instanceof EntityMaid maid && MaidLifecycleService.isPendingRedemption(maid))
            event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPendingAttack(net.minecraftforge.event.entity.player.AttackEntityEvent event) {
        if (event.target instanceof EntityMaid maid && MaidLifecycleService.isPendingRedemption(maid))
            event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPendingDamage(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
        if (event.entityLiving instanceof EntityMaid maid && MaidLifecycleService.isPendingRedemption(maid)
            || event.source.getEntity() instanceof EntityMaid source
                && MaidLifecycleService.isPendingRedemption(source))
            event.setCanceled(true);
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        MaidCaptureService.resume(event.player);
        MaidAcquisitionDelivery.resume(event.player);
        MaidRedemptionDelivery.resume(event.player);
        giveInitial(event.player);
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.player.ticksExisted % 100 == 0) {
            MaidCaptureService.resume(event.player);
            MaidAcquisitionDelivery.resume(event.player);
            MaidRedemptionDelivery.resume(event.player);
            giveInitial(event.player);
        }
    }

    /** 满背包延后，不扔在地上；已有同token物品只补记发放状态 */
    public static boolean giveInitial(EntityPlayer player) {
        if (!MaidAcquisitionConfig.giveInitialSlab || !MaidLifecycleService.canUse(player)) return false;
        return MaidAcquisitionDelivery.gift(player);
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase == TickEvent.Phase.END && !event.world.isRemote && event.world.getTotalWorldTime() % 20 == 0) {
            MaidDeathService.retryPending(event.world);
            MaidFilmRecovery.retryPending(event.world);
        }
    }

    @SubscribeEvent
    public void onWorldUnload(net.minecraftforge.event.world.WorldEvent.Unload event) {
        if (!event.world.isRemote) {
            MaidTileReturns.abandon(event.world);
            MaidDeathService.unload(event.world);
        }
    }

    @SubscribeEvent
    public void onJoin(EntityJoinWorldEvent event) {
        if (event.world.isRemote || !(event.entity instanceof EntityMaid maid)) return;
        // 世界账本已确认死亡时，拒绝旧区块快照重新载入这具死亡实体及其库存
        if (!MaidDeathService.onJoin(maid)) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onSpawn(LivingSpawnEvent.CheckSpawn event) {
        if (event.entityLiving instanceof EntityMaid && (!MaidAcquisitionConfig.naturalSpawning
            || !MaidAcquisitionConfig.allowsDimension(event.world.provider.dimensionId)))
            event.setResult(Event.Result.DENY);
    }
}
