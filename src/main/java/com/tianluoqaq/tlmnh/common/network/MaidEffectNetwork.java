package com.tianluoqaq.tlmnh.common.network;

import net.minecraft.entity.player.EntityPlayerMP;

import com.github.tartaricacid.touhoulittlemaid.inventory.container.AbstractMaidContainer;
import com.github.tartaricacid.touhoulittlemaid.network.message.SendEffectMessage;
import com.tianluoqaq.tlmnh.TLMNotHard;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/** 只向正在查看该女仆的主人发送只读效果，网络线程只入队 */
public final class MaidEffectNetwork {

    private static SimpleNetworkWrapper channel;

    private MaidEffectNetwork() {}

    public static void init() {
        if (channel != null) return;
        channel = NetworkRegistry.INSTANCE.newSimpleChannel("tlmnh_effect1");
        channel.registerMessage(Handler.class, SendEffectMessage.class, 0, Side.CLIENT);
    }

    public static void send(AbstractMaidContainer menu, EntityPlayerMP viewer) {
        if (channel != null && viewer.openContainer == menu && menu.canInteractWith(viewer))
            channel.sendTo(new SendEffectMessage(menu.getMaid(), menu.windowId), viewer);
    }

    public static final class Handler implements IMessageHandler<SendEffectMessage, IMessage> {

        @Override
        public IMessage onMessage(SendEffectMessage message, MessageContext context) {
            if (context.side == Side.CLIENT) TLMNotHard.PROXY.enqueueMaidEffects(message);
            return null;
        }
    }
}
