package com.tianluoqaq.tlmnh.common.network;

import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.util.ChatComponentTranslation;

import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidNaming;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/** 网络线程只保留每个连接的最新请求，服务端tick复核全部权限后提交 */
public final class MaidNamingNetwork {

    private static final ConcurrentHashMap<NetHandlerPlayServer, MaidNameRequest> PENDING = new ConcurrentHashMap<>();
    private static SimpleNetworkWrapper channel;

    private MaidNamingNetwork() {}

    public static void init() {
        if (channel != null) return;
        channel = NetworkRegistry.INSTANCE.newSimpleChannel("tlmnh_name1");
        channel.registerMessage(Handler.class, MaidNameRequest.class, 0, Side.SERVER);
        FMLCommonHandler.instance()
            .bus()
            .register(new MaidNamingNetwork());
    }

    public static void send(MaidNameRequest request) {
        channel.sendToServer(request);
    }

    /** 只从服务器主线程调用，开发夹具与实际网络共用这个入口 */
    public static boolean apply(EntityPlayerMP player, MaidNameRequest request) {
        if (player == null || player.worldObj.isRemote
            || !(player.openContainer instanceof ContainerMaidNaming container)
            || !request.matches(container)) return false;
        boolean accepted = container.submit(player, request.getName(), request.isAlwaysShow());
        if (!accepted) player.addChatMessage(new ChatComponentTranslation("message.tlmnh.naming.denied"));
        player.closeScreen();
        return accepted;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        int processed = 0;
        for (var entry : PENDING.entrySet()) {
            var handler = entry.getKey();
            if (PENDING.remove(handler, entry.getValue()) && handler.netManager.isChannelOpen()
                && handler.playerEntity.playerNetServerHandler == handler)
                apply(handler.playerEntity, entry.getValue());
            if (++processed >= 128) break;
        }
    }

    public static final class Handler implements IMessageHandler<MaidNameRequest, IMessage> {

        @Override
        public IMessage onMessage(MaidNameRequest request, MessageContext context) {
            if (context.side == Side.SERVER) PENDING.put(context.getServerHandler(), request);
            return null;
        }
    }
}
