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
    private final ArrayBlockingQueue<Pending> queue = new ArrayBlockingQueue<>(128);
    private final AtomicInteger generation = new AtomicInteger(), received = new AtomicInteger(),
        overflow = new AtomicInteger();
    private final AtomicBoolean resetRequested = new AtomicBoolean();
    private int attempts, stale, empty;
    private String lastDecision = "none";
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
        lastDecision = "world-reset";
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (resetRequested.getAndSet(false)) reset();
        var minecraft = Minecraft.getMinecraft();
        for (int i = 0; i < 128; i++) {
            var pending = queue.poll();
            if (pending == null) break;
            var message = pending.message;
            lastMessage = message;
            var world = minecraft.theWorld;
            if (pending.generation != generation.get() || world == null
                || world.provider.dimensionId != message.dimension()) {
                stale++;
                lastDecision = "stale-world";
                continue;
            }
            var entity = world.getEntityByID(message.entityId());
            if (!(entity instanceof EntityMaid maid) || !maid.getUniqueID()
                .equals(message.entityUuid())) {
                stale++;
                lastDecision = "stale-entity";
                continue;
            }
            if (BuiltinSoundCatalog.get(message.pack()).events.get(message.event()) == 0) {
                empty++;
                lastDecision = "empty";
                continue;
            }
            lastSound = new MaidSoundInstance(message.pack(), message.event(), maid);
            attempts++;
            lastDecision = "submitted-on-client-tick";
            minecraft.getSoundHandler()
                .playSound(lastSound);
        }
    }

    public JsonObject observation() {
        var result = new JsonObject();
        result.addProperty("lastStoppedUuid", lastStoppedUuid);
        result.addProperty("retainedSoundEntity", lastSound != null);
        result.addProperty(
            "globalPercent",
            com.tianluoqaq.tlmnh.client.gui.MaidClientPreferences.globalMaidSoundFrequency());
        result.addProperty("received", received.get());
        result.addProperty("queued", queue.size());
        result.addProperty("overflow", overflow.get());
        result.addProperty("attempts", attempts);
        result.addProperty("stale", stale);
        result.addProperty("empty", empty);
        result.addProperty("decision", lastDecision);
        result.add("filter", MaidSoundFreqEvent.observation());
        if (lastMessage != null) {
            result.addProperty("event", lastMessage.event());
            result.addProperty("pack", lastMessage.pack());
            result.addProperty(
                "requestedUuid",
                lastMessage.entityUuid()
                    .toString());
            result.addProperty("requestedDimension", lastMessage.dimension());
        }
        if (lastSound != null) {
            var maid = lastSound.getMaid();
            result.addProperty(
                "soundUuid",
                maid.getUniqueID()
                    .toString());
            result.addProperty("soundX", lastSound.getXPosF());
            result.addProperty("soundY", lastSound.getYPosF());
            result.addProperty("soundZ", lastSound.getZPosF());
            result.addProperty(
                "maidFrequency",
                maid.getConfigManager()
                    .getSoundFreq());
            result.addProperty("maidX", maid.posX);
            result.addProperty("maidY", maid.posY);
            result.addProperty("maidZ", maid.posZ);
            result.addProperty("done", lastSound.isDonePlaying());
            result.addProperty(
                "engineReportsPlaying",
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
