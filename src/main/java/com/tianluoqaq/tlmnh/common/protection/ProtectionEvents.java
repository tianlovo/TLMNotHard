package com.tianluoqaq.tlmnh.common.protection;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;

/** 故障日志保留异常栈，聊天按玩家和故障去重，失败后仍可进入世界取回物品 */
public final class ProtectionEvents {

    private static final Map<UUID, Set<String>> NOTIFIED = new java.util.concurrent.ConcurrentHashMap<>();

    public static void register() {
        var events = new ProtectionEvents();
        MinecraftForge.EVENT_BUS.register(events);
        FMLCommonHandler.instance()
            .bus()
            .register(events);
    }

    @SubscribeEvent
    public void load(WorldEvent.Load event) {
        if (event.world.isRemote) return;
        try {
            CompatibilityLedger.verify(event.world);
        } catch (Exception | LinkageError | AssertionError failure) {
            for (var feature : ExternalFeature.values())
                if (!feature.clientOnly) IntegrationGuard.trip(Side.SERVER, feature, failure);
        }
    }

    @SubscribeEvent
    public void login(PlayerEvent.PlayerLoggedInEvent event) {
        NOTIFIED.remove(event.player.getUniqueID());
        warn(event.player);
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        NOTIFIED.remove(event.player.getUniqueID());
    }

    @SubscribeEvent
    public void tick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.player.ticksExisted % 20 == 0) warn(event.player);
    }

    public static void warn(EntityPlayer player) {
        if (player == null || player.worldObj.isRemote) return;
        var notified = NOTIFIED
            .computeIfAbsent(player.getUniqueID(), ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        for (var fault : IntegrationGuard.faults(Side.SERVER)
            .values()) if (notified.add(fault.code)) {
                var message = new ChatComponentTranslation("chat.tlmnh.protection.fallback", fault.code, fault.key);
                message.getChatStyle()
                    .setColor(EnumChatFormatting.GOLD);
                player.addChatMessage(message);
            }
    }
}
