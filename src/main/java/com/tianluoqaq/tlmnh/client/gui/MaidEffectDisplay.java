package com.tianluoqaq.tlmnh.client.gui;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.Minecraft;
import net.minecraftforge.event.world.WorldEvent;

import com.github.tartaricacid.touhoulittlemaid.inventory.container.AbstractMaidContainer;
import com.github.tartaricacid.touhoulittlemaid.network.message.SendEffectMessage;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** GUI效果副本不修改原生药水或实体属性，关闭/换世界后拒绝旧窗口消息 */
@SideOnly(Side.CLIENT)
public final class MaidEffectDisplay {

    public static final MaidEffectDisplay INSTANCE = new MaidEffectDisplay();
    private final ArrayBlockingQueue<Pending> queue = new ArrayBlockingQueue<>(32);
    private final AtomicInteger generation = new AtomicInteger();

    private MaidEffectDisplay() {}

    private static final class Pending {

        final SendEffectMessage message;
        final int generation;

        Pending(SendEffectMessage message, int generation) {
            this.message = message;
            this.generation = generation;
        }
    }

    public void enqueue(SendEffectMessage message) {
        var pending = new Pending(message, generation.get());
        if (!queue.offer(pending)) {
            queue.poll();
            queue.offer(pending);
        }
    }

    private void reset() {
        generation.incrementAndGet();
        queue.clear();
    }

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        reset();
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        reset();
    }

    @SubscribeEvent
    public void onUnload(WorldEvent.Unload event) {
        if (event.world.isRemote) reset();
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getMinecraft();
        for (int i = 0; i < 32; i++) {
            var pending = queue.poll();
            if (pending == null) break;
            var message = pending.message;
            if (pending.generation != generation.get() || mc.theWorld == null
                || mc.thePlayer == null
                || mc.theWorld.provider.dimensionId != message.dimension()) continue;
            if (!(mc.thePlayer.openContainer instanceof AbstractMaidContainer menu)
                || menu.windowId != message.windowId()) continue;
            var maid = menu.getMaid();
            if (maid.worldObj != mc.theWorld || mc.theWorld.getEntityByID(message.entityId()) != maid
                || !maid.getUniqueID()
                    .equals(message.entityUuid())
                || !menu.canInteractWith(mc.thePlayer)) continue;
            var resolved = new java.util.ArrayList<SendEffectMessage.EffectData>();
            for (var effect : message.effects()) {
                int category = effect.category;
                // 1.7.10 的 isBadEffect 被标为客户端专属，在这里用同一包体的药水注册表取色
                if (effect.potionId >= 0 && effect.potionId < net.minecraft.potion.Potion.potionTypes.length) {
                    var potion = net.minecraft.potion.Potion.potionTypes[effect.potionId];
                    if (potion != null && potion.getName()
                        .equals(effect.descriptionId)) category = potion.isBadEffect() ? 1 : 0;
                }
                resolved.add(
                    new SendEffectMessage.EffectData(
                        effect.potionId,
                        effect.descriptionId,
                        effect.amplifier,
                        effect.duration,
                        category));
            }
            menu.setDisplayedEffects(resolved, message.invulnerable());
        }
    }
}
