/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/sound/data/MaidSoundInstance.java, MIT
 * Adaptation: native 1.7.10 MovingSound and generated builtin sound events
 */
package com.github.tartaricacid.touhoulittlemaid.client.sound.data;

import net.minecraft.client.audio.MovingSound;
import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class MaidSoundInstance extends MovingSound {

    private final EntityMaid maid;

    public MaidSoundInstance(String pack, String event, EntityMaid maid) {
        super(new ResourceLocation(pack, event));
        this.maid = maid;
        update();
    }

    @Override
    public void update() {
        if (maid.isDead) donePlaying = true;
        else {
            xPosF = (float) maid.posX;
            yPosF = (float) maid.posY;
            zPosF = (float) maid.posZ;
        }
    }
}
