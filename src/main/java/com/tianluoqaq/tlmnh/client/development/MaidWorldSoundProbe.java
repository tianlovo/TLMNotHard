package com.tianluoqaq.tlmnh.client.development;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.util.ResourceLocation;

import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.client.gui.MaidClientPreferences;
import com.tianluoqaq.tlmnh.client.sound.MaidWorldSoundPlayer;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class MaidWorldSoundProbe {

    private static PositionedSoundRecord lastVanilla;

    private MaidWorldSoundProbe() {}

    public static boolean vanillaPlaying() {
        return lastVanilla != null && Minecraft.getMinecraft()
            .getSoundHandler()
            .isSoundPlaying(lastVanilla);
    }

    public static JsonObject perform(String action) {
        var minecraft = Minecraft.getMinecraft();
        var player = minecraft.thePlayer;
        if (!Boolean.getBoolean("tlmnh.devCommands") || !AnimationLab.isLab(minecraft.theWorld)
            || player == null
            || !player.capabilities.disableDamage
            || !player.noClip
            || !player.capabilities.isFlying)
            throw new IllegalStateException("Protected lab observer required for world sound probe");
        var result = new JsonObject();
        switch (action) {
            case "global_0" -> MaidClientPreferences.setGlobalMaidSoundFrequency(0);
            case "global_100" -> MaidClientPreferences.setGlobalMaidSoundFrequency(100);
            case "vanilla" -> {
                var sound = PositionedSoundRecord.func_147674_a(new ResourceLocation("note.harp"), 1);
                lastVanilla = sound;
                minecraft.getSoundHandler()
                    .playSound(sound);
                result.addProperty(
                    "vanillaEngineReportsPlaying",
                    minecraft.getSoundHandler()
                        .isSoundPlaying(sound));
            }
            case "close_gui" -> player.closeScreen();
            case "disconnect" -> {
                minecraft.theWorld.sendQuittingDisconnectingPacket();
                minecraft.loadWorld(null);
                minecraft.displayGuiScreen(new GuiMainMenu());
            }
            default -> throw new IllegalArgumentException("Unknown world sound client fixture");
        }
        result.add("worldSounds", MaidWorldSoundPlayer.INSTANCE.observation());
        return result;
    }
}
