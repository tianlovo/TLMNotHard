package com.tianluoqaq.tlmnh.client.development;

import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.audio.SoundManager;
import net.minecraft.client.audio.SoundPoolEntry;

import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.client.sound.MaidWorldSoundPlayer;

import cpw.mods.fml.relauncher.ReflectionHelper;
import paulscode.sound.SoundSystem;

/** 只读原生声音对象、SoundManager来源映射和SoundSystem实际playing，不将投递计数当作播放 */
final class NativeSoundPlaybackObservation {

    private static String previousSource = "";

    private NativeSoundPlaybackObservation() {}

    static JsonObject capture() {
        var result = new JsonObject();
        result.addProperty("available", false);
        if (!Boolean.getBoolean("tlmnh.devCommands")) return result;
        try {
            ISound sound = ReflectionHelper
                .getPrivateValue(MaidWorldSoundPlayer.class, MaidWorldSoundPlayer.INSTANCE, "lastSound");
            result.addProperty("retainedSound", sound != null);
            var handler = Minecraft.getMinecraft()
                .getSoundHandler();
            SoundManager manager = ReflectionHelper
                .getPrivateValue(SoundHandler.class, handler, "field_147694_f", "sndManager");
            SoundSystem system = ReflectionHelper
                .getPrivateValue(SoundManager.class, manager, "field_148620_e", "sndSystem");
            Map<?, ?> inverse = ReflectionHelper
                .getPrivateValue(SoundManager.class, manager, "field_148630_i", "invPlayingSounds");
            Map<?, ?> entries = ReflectionHelper
                .getPrivateValue(SoundManager.class, manager, "field_148627_j", "playingSoundPoolEntries");
            result.addProperty("available", system != null);
            result.addProperty("handlerReportsPlaying", sound != null && handler.isSoundPlaying(sound));
            Object source = inverse.get(sound);
            if (source instanceof String) previousSource = (String) source;
            result.addProperty("nativeSource", source instanceof String ? (String) source : "");
            result.addProperty(
                "nativeSystemPlaying",
                system != null && source instanceof String && system.playing((String) source));
            result.addProperty("previousNativeSource", previousSource);
            result.addProperty(
                "previousNativeSystemPlaying",
                system != null && !previousSource.isEmpty() && system.playing(previousSource));
            if (sound != null) {
                result.addProperty(
                    "event",
                    sound.getPositionedSoundLocation()
                        .toString());
                result.addProperty("x", sound.getXPosF());
                result.addProperty("y", sound.getYPosF());
                result.addProperty("z", sound.getZPosF());
                Object entry = entries.get(sound);
                result.addProperty(
                    "resolvedResource",
                    entry instanceof SoundPoolEntry ? ((SoundPoolEntry) entry).getSoundPoolEntryLocation()
                        .toString() : "");
            }
        } catch (RuntimeException | LinkageError failure) {
            result.addProperty("available", false);
            result.addProperty(
                "failure",
                failure.getClass()
                    .getName());
        }
        return result;
    }
}
