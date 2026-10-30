package com.tianluoqaq.tlmnh.common.lifecycle;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingSpawnEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** FML发放事件与Forge死亡/生成事件；不参与女仆行为决策 */
public final class MaidLifecycleEvents {

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        giveInitial(event.player);
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.player.ticksExisted % 100 == 0) giveInitial(event.player);
    }

    /** 满背包延后，不扔在地上；已有同token物品只补记发放状态 */
    public static boolean giveInitial(EntityPlayer player) {
        if (!MaidAcquisitionConfig.giveInitialSlab || !MaidLifecycleService.canUse(player)) return false;
        var ledger = MaidLifecycleData.get(player.worldObj);
        var token = ledger.initialToken(player.getUniqueID());
        if (ledger.delivered(token)) return false;
        int empty = -1;
        for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            var stack = player.inventory.mainInventory[i];
            if (token.equals(MaidLifecycleItems.token(stack))) {
                ledger.markDelivered(token);
                return false;
            }
            if (stack == null && empty < 0) empty = i;
        }
        if (empty < 0) return false;
        player.inventory.setInventorySlotContents(
            empty,
            MaidLifecycleItems.credential(MaidResurrectionContent.INITIAL_SLAB, token, player.getUniqueID()));
        ledger.markDelivered(token);
        player.inventory.markDirty();
        return true;
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase == TickEvent.Phase.END && !event.world.isRemote && event.world.getTotalWorldTime() % 20 == 0) {
            MaidDeathService.retryPending(event.world);
            MaidFilmRecovery.retryPending(event.world);
        }
    }

    @SubscribeEvent
    public void onJoin(EntityJoinWorldEvent event) {
        if (event.world.isRemote || !(event.entity instanceof EntityMaid maid)) return;
        // 世界账本已确认死亡时，拒绝旧区块快照重新载入这具死亡实体及其库存
        var ledger = MaidLifecycleData.get(event.world);
        var token = ledger.deathToken(maid.getUniqueID());
        if (token != null && ledger.delivered(token)) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onSpawn(LivingSpawnEvent.CheckSpawn event) {
        if (event.entityLiving instanceof EntityMaid && (!MaidAcquisitionConfig.naturalSpawning
            || !MaidAcquisitionConfig.allowsDimension(event.world.provider.dimensionId)))
            event.setResult(Event.Result.DENY);
    }
}
