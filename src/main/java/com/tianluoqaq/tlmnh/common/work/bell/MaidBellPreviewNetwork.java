package com.tianluoqaq.tlmnh.common.work.bell;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

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

/**
 * 主人潜行注视所属女仆时的铃铛范围只读预览网络通道：
 * 服务端按连接合并待查请求并限制单连接冷却与单刻总处理数，客户端在主线程回调快照接收器
 */
public final class MaidBellPreviewNetwork {

    private static final ConcurrentHashMap<NetHandlerPlayServer, PendingQuery> PENDING = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<BellPreviewSnapshot> CLIENT_SNAPSHOTS = new ConcurrentLinkedQueue<>();
    private static final HashMap<NetHandlerPlayServer, Long> NEXT_ALLOWED_TICK = new HashMap<>();

    private static SimpleNetworkWrapper channel;
    private static volatile Consumer<BellPreviewSnapshot> clientPreviewSink = snapshot -> {};
    private static volatile long clientTickCounter;
    private static volatile int lastRequestedMaidId = BellPreviewProtocol.UNSET_ENTITY_ID;
    private static volatile long lastRequestClientTick = BellPreviewProtocol.UNSET_TICK;

    private MaidBellPreviewNetwork() {}

    public static void bindClientPreviewSink(Consumer<BellPreviewSnapshot> sink) {
        clientPreviewSink = sink == null ? snapshot -> {} : sink;
    }

    public static void init() {
        if (channel != null) return;
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(BellPreviewProtocol.CHANNEL_NAME);
        channel.registerMessage(
            RequestHandler.class,
            RequestMessage.class,
            BellPreviewProtocol.DISCRIMINATOR_REQUEST,
            Side.SERVER);
        channel.registerMessage(
            SnapshotHandler.class,
            SnapshotMessage.class,
            BellPreviewProtocol.DISCRIMINATOR_SNAPSHOT,
            Side.CLIENT);
        FMLCommonHandler.instance()
            .bus()
            .register(new MaidBellPreviewNetwork());
    }

    /**
     * 客户端在主人潜行注视近距离所属女仆时请求范围预览：
     * 切换注视目标立即发送，持续注视同一女仆按固定间隔刷新以反映重绑或摘除变化
     */
    public static boolean requestPreview(int maidEntityId) {
        if (channel == null || maidEntityId < 0) return false;
        long now = clientTickCounter;
        if (maidEntityId == lastRequestedMaidId
            && now - lastRequestClientTick < BellPreviewProtocol.CLIENT_SAME_TARGET_COOLDOWN_TICKS) {
            return false;
        }
        lastRequestedMaidId = maidEntityId;
        lastRequestClientTick = now;
        channel.sendToServer(new RequestMessage(maidEntityId));
        return true;
    }

    public static void resetClientState() {
        lastRequestedMaidId = BellPreviewProtocol.UNSET_ENTITY_ID;
        lastRequestClientTick = BellPreviewProtocol.UNSET_TICK;
        CLIENT_SNAPSHOTS.clear();
    }

    public static EntityMaid resolvePreviewableMaid(EntityPlayerMP player, int maidEntityId) {
        if (player == null || player.worldObj == null || player.worldObj.isRemote || !player.isEntityAlive()) {
            return null;
        }
        if (player.theItemInWorldManager == null || player.theItemInWorldManager.getGameType()
            .getID() == BellPreviewProtocol.SPECTATOR_GAME_TYPE_ID) {
            return null;
        }
        if (!player.isSneaking()) return null;
        Entity entity = player.worldObj.getEntityByID(maidEntityId);
        if (!(entity instanceof EntityMaid maid)) return null;
        if (!maid.isEntityAlive() || maid.worldObj != player.worldObj || !maid.isOwner(player)) return null;
        if (player.getDistanceSqToEntity(maid) > BellPreviewProtocol.MAX_PREVIEW_DIST_SQ) return null;
        return maid;
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        NEXT_ALLOWED_TICK.entrySet()
            .removeIf(entry -> !entry.getKey().netManager.isChannelOpen());

        int processed = 0;
        for (var entry : PENDING.entrySet()) {
            NetHandlerPlayServer handler = entry.getKey();
            PendingQuery query = entry.getValue();
            if (!handler.netManager.isChannelOpen() || handler.playerEntity == null
                || handler.playerEntity.playerNetServerHandler != handler
                || handler.playerEntity.worldObj == null) {
                PENDING.remove(handler, query);
                continue;
            }
            EntityPlayerMP player = handler.playerEntity;
            long now = player.worldObj.getTotalWorldTime();
            Long nextAllowed = NEXT_ALLOWED_TICK.get(handler);
            if (nextAllowed != null && now < nextAllowed) {
                continue;
            }
            if (!player.isSneaking()) {
                if (query.remainingSneakGrace > 0) {
                    query.remainingSneakGrace -= 1;
                    continue;
                }
                PENDING.remove(handler, query);
                continue;
            }
            if (PENDING.remove(handler, query)) {
                NEXT_ALLOWED_TICK.put(handler, now + BellPreviewProtocol.SERVER_COOLDOWN_TICKS);
                EntityMaid maid = resolvePreviewableMaid(player, query.maidEntityId);
                if (maid != null && channel != null) {
                    channel.sendTo(new SnapshotMessage(BellPreviewSnapshot.captureFromMaid(maid)), player);
                }
            }
            if (++processed >= BellPreviewProtocol.MAX_PROCESSED_PER_TICK) break;
        }
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        clientTickCounter += 1L;
        int processed = 0;
        BellPreviewSnapshot next;
        while (processed < BellPreviewProtocol.MAX_PROCESSED_PER_TICK && (next = CLIENT_SNAPSHOTS.poll()) != null) {
            clientPreviewSink.accept(next);
            processed++;
        }
    }

    private static void enqueueClientSnapshot(BellPreviewSnapshot snapshot) {
        if (snapshot == null) return;
        while (CLIENT_SNAPSHOTS.size() >= BellPreviewProtocol.MAX_CLIENT_QUEUE_SIZE) {
            if (CLIENT_SNAPSHOTS.poll() == null) break;
        }
        CLIENT_SNAPSHOTS.offer(snapshot);
    }

    private static final class PendingQuery {

        final int maidEntityId;
        int remainingSneakGrace;

        PendingQuery(int maidEntityId, int remainingSneakGrace) {
            this.maidEntityId = maidEntityId;
            this.remainingSneakGrace = remainingSneakGrace;
        }
    }

    public static final class RequestMessage implements IMessage {

        private int maidEntityId = BellPreviewProtocol.UNSET_ENTITY_ID;

        public RequestMessage() {}

        public RequestMessage(int maidEntityId) {
            this.maidEntityId = maidEntityId;
        }

        public int maidEntityId() {
            return maidEntityId;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            this.maidEntityId = buf.readInt();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(maidEntityId);
        }
    }

    public static final class SnapshotMessage implements IMessage {

        private BellPreviewSnapshot snapshot;

        public SnapshotMessage() {}

        public SnapshotMessage(BellPreviewSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        public BellPreviewSnapshot snapshot() {
            return snapshot;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            this.snapshot = BellPreviewSnapshot.readFrom(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            snapshot.writeTo(buf);
        }
    }

    public static final class RequestHandler implements IMessageHandler<RequestMessage, IMessage> {

        @Override
        public IMessage onMessage(RequestMessage message, MessageContext ctx) {
            if (ctx.side == Side.SERVER && message != null && message.maidEntityId() >= 0) {
                PENDING.put(
                    ctx.getServerHandler(),
                    new PendingQuery(message.maidEntityId(), BellPreviewProtocol.SNEAK_SYNC_GRACE_TICKS));
            }
            return null;
        }
    }

    public static final class SnapshotHandler implements IMessageHandler<SnapshotMessage, IMessage> {

        @Override
        public IMessage onMessage(SnapshotMessage message, MessageContext ctx) {
            if (ctx.side == Side.CLIENT && message != null && message.snapshot() != null) {
                enqueueClientSnapshot(message.snapshot());
            }
            return null;
        }
    }
}
