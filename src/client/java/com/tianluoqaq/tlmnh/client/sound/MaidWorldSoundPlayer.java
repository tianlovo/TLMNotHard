package com.tianluoqaq.tlmnh.client.sound;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.Minecraft;
import net.minecraftforge.event.world.WorldEvent;

import com.github.tartaricacid.touhoulittlemaid.client.event.MaidSoundFreqEvent;
import com.github.tartaricacid.touhoulittlemaid.client.sound.data.MaidSoundInstance;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.network.message.PlayMaidSoundMessage;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.client.gui.config.MaidClientPreferences;
import com.tianluoqaq.tlmnh.common.sound.BuiltinSoundCatalog;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 网络线程只入队，客户端tick复核当前世界与实体后交给原生引擎 */
@SideOnly(Side.CLIENT)
public final class MaidWorldSoundPlayer {

    public static final MaidWorldSoundPlayer INSTANCE = new MaidWorldSoundPlayer();
    private final ArrayBlockingQueue<Pending> queue = new ArrayBlockingQueue<>(ClientSoundTuning.WORLD_SOUND_DOMAIN_PARAM);
    private final AtomicInteger generation = new AtomicInteger(), received = new AtomicInteger(),
        overflow = new AtomicInteger();
    private final AtomicBoolean resetRequested = new AtomicBoolean();
    private int attempts, stale, empty;
    private String lastDecision = ClientSoundIds.ID_NONE;
    private PlayMaidSoundMessage lastMessage;
    private MaidSoundInstance lastSound;
    private String lastStoppedUuid = "";

    private MaidWorldSoundPlayer() {}

    public void enqueue(PlayMaidSoundMessage message) {
        received.incrementAndGet();
        if (!queue.offer(new Pending(message, generation.get()))) overflow.incrementAndGet();
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        generation.incrementAndGet();
        queue.clear();
        resetRequested.set(true);
    }

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        generation.incrementAndGet();
        queue.clear();
        resetRequested.set(true);
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world.isRemote && (event.world == Minecraft.getMinecraft().theWorld
            || lastSound != null && lastSound.getMaid().worldObj == event.world)) {
            generation.incrementAndGet();
            reset();
        }
    }

    private void reset() {
        queue.clear();
        if (lastSound != null) {
            lastStoppedUuid = lastSound.getMaid()
                .getUniqueID()
                .toString();
            lastSound.stop();
            Minecraft.getMinecraft()
                .getSoundHandler()
                .stopSound(lastSound);
            lastSound = null;
        }
        lastDecision = ClientSoundIds.ID_WORLD_RESET;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (resetRequested.getAndSet(false)) reset();
        var minecraft = Minecraft.getMinecraft();
        for (int i = 0; i < ClientSoundTuning.WORLD_SOUND_DOMAIN_PARAM; i++) {
            var pending = queue.poll();
            if (pending == null) break;
            var message = pending.message;
            lastMessage = message;
            var world = minecraft.theWorld;
            if (pending.generation != generation.get() || world == null
                || world.provider.dimensionId != message.dimension()) {
                stale++;
                lastDecision = ClientSoundIds.ID_STALE_WORLD;
                continue;
            }
            var entity = world.getEntityByID(message.entityId());
            if (!(entity instanceof EntityMaid maid) || !maid.getUniqueID()
                .equals(message.entityUuid())) {
                stale++;
                lastDecision = ClientSoundIds.ID_STALE_ENTITY;
                continue;
            }
            if (BuiltinSoundCatalog.get(message.pack()).events.get(message.event()) == 0) {
                empty++;
                lastDecision = ClientSoundIds.ID_EMPTY;
                continue;
            }
            lastSound = new MaidSoundInstance(message.pack(), message.event(), maid);
            attempts++;
            lastDecision = ClientSoundIds.ID_SUBMITTED_ON_CLIENT_TICK;
            minecraft.getSoundHandler()
                .playSound(lastSound);
        }
    }

    public JsonObject observation() {
        var result = new JsonObject();
        result.addProperty(ClientSoundIds.ID_LASTSTOPPEDUUID, lastStoppedUuid);
        result.addProperty(ClientSoundIds.ID_RETAINEDSOUNDENTITY, lastSound != null);
        result.addProperty(ClientSoundIds.ID_GLOBALPERCENT, MaidClientPreferences.globalMaidSoundFrequency());
        result.addProperty(ClientSoundIds.ID_RECEIVED, received.get());
        result.addProperty(ClientSoundIds.ID_QUEUED, queue.size());
        result.addProperty(ClientSoundIds.ID_OVERFLOW, overflow.get());
        result.addProperty(ClientSoundIds.ID_ATTEMPTS, attempts);
        result.addProperty(ClientSoundIds.ID_STALE, stale);
        result.addProperty(ClientSoundIds.ID_EMPTY, empty);
        result.addProperty(ClientSoundIds.ID_DECISION, lastDecision);
        result.add(ClientSoundIds.ID_FILTER, MaidSoundFreqEvent.observation());
        if (lastMessage != null) {
            result.addProperty(ClientSoundIds.ID_EVENT, lastMessage.event());
            result.addProperty(ClientSoundIds.ID_PACK, lastMessage.pack());
            result.addProperty(
                ClientSoundIds.ID_REQUESTEDUUID,
                lastMessage.entityUuid()
                    .toString());
            result.addProperty(ClientSoundIds.ID_REQUESTEDDIMENSION, lastMessage.dimension());
        }
        if (lastSound != null) {
            var maid = lastSound.getMaid();
            result.addProperty(
                ClientSoundIds.ID_SOUNDUUID,
                maid.getUniqueID()
                    .toString());
            result.addProperty(ClientSoundIds.ID_SOUNDX, lastSound.getXPosF());
            result.addProperty(ClientSoundIds.ID_SOUNDY, lastSound.getYPosF());
            result.addProperty(ClientSoundIds.ID_SOUNDZ, lastSound.getZPosF());
            result.addProperty(
                ClientSoundIds.ID_MAIDFREQUENCY,
                maid.getConfigManager()
                    .getSoundFreq());
            result.addProperty(ClientSoundIds.ID_MAIDX, maid.posX);
            result.addProperty(ClientSoundIds.ID_MAIDY, maid.posY);
            result.addProperty(ClientSoundIds.ID_MAIDZ, maid.posZ);
            result.addProperty(ClientSoundIds.ID_DONE, lastSound.isDonePlaying());
            result.addProperty(
                ClientSoundIds.ID_ENGINEREPORTSPLAYING,
                Minecraft.getMinecraft()
                    .getSoundHandler()
                    .isSoundPlaying(lastSound));
        }
        return result;
    }

    private static final class Pending {

        private final PlayMaidSoundMessage message;
        private final int generation;

        private Pending(PlayMaidSoundMessage message, int generation) {
            this.message = message;
            this.generation = generation;
        }
    }
}
