package com.tianluoqaq.tlmnh.common.work.container;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.util.ChatComponentTranslation;

import com.tianluoqaq.tlmnh.common.inventory.container.MaidContainerProtocol;
import com.tianluoqaq.tlmnh.common.work.breeding.config.BreedingTaskConfigData;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/** 育种配置容器双端网络通道（与 MaidTaskConfigNetwork 保持同一主线程入队消费语义） */
public final class MaidBreedingConfigNetwork {

    private static final ConcurrentHashMap<NetHandlerPlayServer, SubmitMessage> PENDING = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<MaidBreedingConfigPayload> CLIENT_SNAPSHOTS = new ConcurrentLinkedQueue<>();
    private static final HashMap<NetHandlerPlayServer, BreedingTaskConfigContainer> CLOSED = new HashMap<>();
    private static SimpleNetworkWrapper channel;
    private static volatile Consumer<MaidBreedingConfigPayload> clientSnapshotSink = payload -> {};

    private MaidBreedingConfigNetwork() {}

    public static void bindClientSnapshotSink(Consumer<MaidBreedingConfigPayload> sink) {
        clientSnapshotSink = sink == null ? payload -> {} : sink;
    }

    public static void init() {
        if (channel != null) return;
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(BreedingContainerProtocol.CHANNEL_NAME);
        channel.registerMessage(
            SubmitHandler.class,
            SubmitMessage.class,
            BreedingContainerProtocol.DISCRIMINATOR_SUBMIT,
            Side.SERVER);
        channel.registerMessage(
            SnapshotHandler.class,
            SnapshotMessage.class,
            BreedingContainerProtocol.DISCRIMINATOR_SNAPSHOT,
            Side.CLIENT);
        FMLCommonHandler.instance()
            .bus()
            .register(new MaidBreedingConfigNetwork());
    }

    public static void sendSubmit(MaidBreedingConfigPayload payload, int nextAction) {
        if (channel != null) {
            channel.sendToServer(new SubmitMessage(payload, nextAction));
        }
    }

    public static void closed(BreedingTaskConfigContainer menu, EntityPlayerMP player) {
        CLOSED.put(player.playerNetServerHandler, menu);
    }

    public static void snapshot(BreedingTaskConfigContainer menu, EntityPlayerMP player, BreedingTaskConfigData live) {
        if (channel == null || player.openContainer != menu || !menu.canInteractWith(player)) return;
        channel.sendTo(
            new SnapshotMessage(MaidBreedingConfigPayload.from(menu, live, menu.serverEditable())),
            player);
    }

    public static boolean navigates(int action) {
        return action >= MaidContainerProtocol.ACTION_IDLE && action <= MaidContainerProtocol.ACTION_RANGED
            || action == MaidContainerProtocol.ACTION_MAINTENANCE_HATCH
            || action == MaidContainerProtocol.ACTION_CROPSNH_BREEDING
            || action == MaidContainerProtocol.ACTION_ORIGINAL_MAIN
            || action == MaidContainerProtocol.ACTION_BAUBLE
            || action == MaidContainerProtocol.ACTION_CONFIG
            || action == MaidContainerProtocol.ACTION_MODELS
            || action == MaidContainerProtocol.ACTION_SOUNDS;
    }

    public static boolean apply(EntityPlayerMP player, SubmitMessage message) {
        if (player == null || player.worldObj.isRemote || message == null || message.payload() == null) return false;
        MaidBreedingConfigPayload payload = message.payload();
        BreedingTaskConfigContainer menu = player.openContainer instanceof BreedingTaskConfigContainer current
            && payload.matchesIdentity(current) ? current : CLOSED.get(player.playerNetServerHandler);
        int action = message.nextAction();
        boolean accepted = menu != null
            && (action == BreedingContainerProtocol.NO_NEXT_ACTION
                || navigates(action) && player.openContainer == menu)
            && menu.submit(player, payload);
        if (accepted && action != BreedingContainerProtocol.NO_NEXT_ACTION) {
            menu.enchantItem(player, action);
        }
        if (accepted) {
            CLOSED.remove(player.playerNetServerHandler, menu);
        } else {
            player.addChatMessage(new ChatComponentTranslation(BreedingContainerProtocol.MSG_CONFIG_DENIED));
        }
        return accepted;
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        int processed = 0;
        for (var entry : PENDING.entrySet()) {
            var handler = entry.getKey();
            if (PENDING.remove(handler, entry.getValue()) && handler.netManager.isChannelOpen()
                && handler.playerEntity.playerNetServerHandler == handler) {
                apply(handler.playerEntity, entry.getValue());
            }
            if (++processed >= BreedingContainerProtocol.MAX_PROCESSED_PER_TICK) break;
        }
        CLOSED.entrySet()
            .removeIf(entry -> !entry.getKey().netManager.isChannelOpen() || entry.getValue().expired());
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        int processed = BreedingContainerProtocol.DISCRIMINATOR_SUBMIT;
        MaidBreedingConfigPayload next;
        while (processed < BreedingContainerProtocol.MAX_PROCESSED_PER_TICK
            && (next = CLIENT_SNAPSHOTS.poll()) != null) {
            clientSnapshotSink.accept(next);
            processed++;
        }
    }

    public static final class SubmitMessage implements IMessage {

        private MaidBreedingConfigPayload payload;
        private int nextAction = BreedingContainerProtocol.NO_NEXT_ACTION;

        public SubmitMessage() {}

        public SubmitMessage(MaidBreedingConfigPayload payload, int nextAction) {
            this.payload = payload;
            this.nextAction = nextAction;
        }

        public MaidBreedingConfigPayload payload() {
            return payload;
        }

        public int nextAction() {
            return nextAction;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            this.payload = MaidBreedingConfigPayload.readFrom(buf);
            this.nextAction = buf.readInt();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            payload.writeTo(buf);
            buf.writeInt(nextAction);
        }
    }

    public static final class SnapshotMessage implements IMessage {

        private MaidBreedingConfigPayload payload;

        public SnapshotMessage() {}

        public SnapshotMessage(MaidBreedingConfigPayload payload) {
            this.payload = payload;
        }

        public MaidBreedingConfigPayload payload() {
            return payload;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            this.payload = MaidBreedingConfigPayload.readFrom(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            payload.writeTo(buf);
        }
    }

    public static final class SubmitHandler implements IMessageHandler<SubmitMessage, IMessage> {

        @Override
        public IMessage onMessage(SubmitMessage message, MessageContext ctx) {
            if (ctx.side == Side.SERVER) PENDING.put(ctx.getServerHandler(), message);
            return null;
        }
    }

    public static final class SnapshotHandler implements IMessageHandler<SnapshotMessage, IMessage> {

        @Override
        public IMessage onMessage(SnapshotMessage message, MessageContext ctx) {
            if (ctx.side == Side.CLIENT && message.payload() != null) {
                CLIENT_SNAPSHOTS.offer(message.payload());
            }
            return null;
        }
    }
}
