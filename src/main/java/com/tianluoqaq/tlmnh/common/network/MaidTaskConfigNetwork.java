package com.tianluoqaq.tlmnh.common.network;

import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.util.ChatComponentTranslation;

import com.github.tartaricacid.touhoulittlemaid.inventory.container.task.TaskConfigContainer;
import com.github.tartaricacid.touhoulittlemaid.network.message.SetAttackListMessage;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaid;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/** 网络线程只入队；关闭消息到达后仍使用服务器保存的一次性原窗口会话 */
public final class MaidTaskConfigNetwork {

    private static final ConcurrentHashMap<NetHandlerPlayServer, SetAttackListMessage> PENDING = new ConcurrentHashMap<>();
    private static final HashMap<NetHandlerPlayServer, TaskConfigContainer> CLOSED = new HashMap<>();
    private static SimpleNetworkWrapper channel;

    private MaidTaskConfigNetwork() {}

    public static void init() {
        if (channel != null) return;
        channel = NetworkRegistry.INSTANCE.newSimpleChannel("tlmnh_task1");
        channel.registerMessage(EditHandler.class, SetAttackListMessage.class, 0, Side.SERVER);
        channel.registerMessage(SnapshotHandler.class, MaidAttackSnapshot.class, 1, Side.CLIENT);
        FMLCommonHandler.instance()
            .bus()
            .register(new MaidTaskConfigNetwork());
    }

    public static void send(SetAttackListMessage message) {
        channel.sendToServer(message);
    }

    public static void closed(TaskConfigContainer menu, EntityPlayerMP player) {
        if (menu.attackPage()) CLOSED.put(player.playerNetServerHandler, menu);
    }

    public static void snapshot(TaskConfigContainer menu, EntityPlayerMP player) {
        if (channel == null || player.openContainer != menu || !menu.canInteractWith(player)) return;
        var groups = menu.getMaid()
            .getAttackListData()
            .attackGroups();
        boolean fits = MaidAttackListPayload.fits(groups);
        channel.sendTo(
            new MaidAttackSnapshot(
                MaidAttackListPayload
                    .from(menu, fits ? groups : Collections.emptyMap(), fits && menu.serverEditable())),
            player);
    }

    public static boolean navigates(int action) {
        return action >= ContainerMaid.ACTION_IDLE && action <= ContainerMaid.ACTION_RANGED
            || action == ContainerMaid.ACTION_ORIGINAL_MAIN
            || action == ContainerMaid.ACTION_CONFIG
            || action == ContainerMaid.ACTION_MODELS
            || action == ContainerMaid.ACTION_SOUNDS;
    }

    /** 主线程入口，与开发夹具共用真实会话和提交条件 */
    public static boolean apply(EntityPlayerMP player, SetAttackListMessage message) {
        if (player == null || player.worldObj.isRemote || message == null || message.payload() == null) return false;
        var payload = message.payload();
        TaskConfigContainer menu = player.openContainer instanceof TaskConfigContainer current
            && payload.matchesIdentity(current) ? current : CLOSED.get(player.playerNetServerHandler);
        int action = message.nextAction();
        boolean accepted = menu != null && (action == -1 || navigates(action) && player.openContainer == menu)
            && menu.submit(player, payload);
        if (accepted && action != -1) menu.enchantItem(player, action);
        if (accepted) CLOSED.remove(player.playerNetServerHandler, menu);
        else player.addChatMessage(new ChatComponentTranslation("message.tlmnh.attack_config.denied"));
        return accepted;
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        int processed = 0;
        for (var entry : PENDING.entrySet()) {
            var handler = entry.getKey();
            if (PENDING.remove(handler, entry.getValue()) && handler.netManager.isChannelOpen()
                && handler.playerEntity.playerNetServerHandler == handler)
                apply(handler.playerEntity, entry.getValue());
            if (++processed >= 128) break;
        }
        CLOSED.entrySet()
            .removeIf(
                entry -> !entry.getKey().netManager.isChannelOpen() || entry.getValue()
                    .expired());
    }

    public static final class EditHandler implements IMessageHandler<SetAttackListMessage, IMessage> {

        @Override
        public IMessage onMessage(SetAttackListMessage message, MessageContext context) {
            if (context.side == Side.SERVER) PENDING.put(context.getServerHandler(), message);
            return null;
        }
    }

    public static final class SnapshotHandler implements IMessageHandler<MaidAttackSnapshot, IMessage> {

        @Override
        public IMessage onMessage(MaidAttackSnapshot message, MessageContext context) {
            if (context.side == Side.CLIENT) TLMNotHard.PROXY.enqueueMaidAttackSnapshot(message);
            return null;
        }
    }
}
