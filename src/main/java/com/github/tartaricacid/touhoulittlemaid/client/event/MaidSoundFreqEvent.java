/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/event/MaidSoundFreqEvent.java, MIT
 * Adaptation: Forge 1.7.10 PlaySoundEvent17 and local client preference
 */
package com.github.tartaricacid.touhoulittlemaid.client.event;

import net.minecraftforge.client.event.sound.PlaySoundEvent17;

import com.github.tartaricacid.touhoulittlemaid.client.sound.data.MaidSoundInstance;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.client.gui.MaidClientPreferences;
import com.tianluoqaq.tlmnh.common.sound.MaidSoundFrequency;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class MaidSoundFreqEvent {

    private static int allowed, denied, legacyLocalVoiceAttempts;
    private static boolean lastTest;
    private static String lastDecision = "none";
    private static float lastFrequency;
    private static int lastGlobal;

    @SubscribeEvent
    public void onPlaySoundEvent(PlaySoundEvent17 event) {
        if (!(event.sound instanceof MaidSoundInstance instance)) {
            if (Boolean.getBoolean("tlmnh.devCommands") && event.sound != null) {
                var location = event.sound.getPositionedSoundLocation();
                if (location.getResourceDomain()
                    .equals("touhou_little_maid")
                    && location.getResourcePath()
                        .startsWith("maid."))
                    legacyLocalVoiceAttempts++;
            }
            return;
        }
        lastTest = instance.isTestSound();
        lastFrequency = instance.getMaid()
            .getConfigManager()
            .getSoundFreq();
        lastGlobal = MaidClientPreferences.globalMaidSoundFrequency();
        boolean accepts = instance.canPlaySound()
            && MaidSoundFrequency.allow(lastFrequency, lastGlobal, lastTest, Math::random);
        lastDecision = accepts ? "allowed" : instance.canPlaySound() ? "frequency" : "silent";
        if (accepts) allowed++;
        else {
            denied++;
            event.result = null;
        }
    }

    public static JsonObject observation() {
        var result = new JsonObject();
        result.addProperty("legacyLocalVoiceAttempts", legacyLocalVoiceAttempts);
        result.addProperty("allowed", allowed);
        result.addProperty("denied", denied);
        result.addProperty("lastDecision", lastDecision);
        result.addProperty("testSound", lastTest);
        result.addProperty("maidFrequency", lastFrequency);
        result.addProperty("globalPercent", lastGlobal);
        return result;
    }
}
