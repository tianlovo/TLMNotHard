package com.tianluoqaq.tlmnh.common.network;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.network.message.PlayMaidSoundMessage;
import com.tianluoqaq.tlmnh.TLMNotHard;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/** 服务端按原版16格范围发送，客户端网络回调只投递，不加载音频引擎类型 */
public final class MaidSoundNetwork {

    private static SimpleNetworkWrapper channel;

    private MaidSoundNetwork() {}

    public static void init() {
        if (channel != null) return;
        channel = NetworkRegistry.INSTANCE.newSimpleChannel("tlmnh_sound1");
        channel.registerMessage(Handler.class, PlayMaidSoundMessage.class, 0, Side.CLIENT);
    }

    public static void send(EntityMaid maid, PlayMaidSoundMessage message) {
        if (channel == null || maid.worldObj == null || maid.worldObj.isRemote) return;
        channel.sendToAllAround(
            message,
            new NetworkRegistry.TargetPoint(maid.worldObj.provider.dimensionId, maid.posX, maid.posY, maid.posZ, 16));
    }

    public static final class Handler implements IMessageHandler<PlayMaidSoundMessage, IMessage> {

        @Override
        public IMessage onMessage(PlayMaidSoundMessage message, MessageContext context) {
            if (context.side == Side.CLIENT) TLMNotHard.PROXY.enqueueMaidSound(message);
            return null;
        }
    }
}
