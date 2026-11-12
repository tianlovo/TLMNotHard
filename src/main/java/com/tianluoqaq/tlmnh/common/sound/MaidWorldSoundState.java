package com.tianluoqaq.tlmnh.common.sound;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.DamageSource;
import net.minecraftforge.common.MinecraftForge;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidPlaySoundEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.util.SoundUtil;

/** 原版世界语音的短期计数；伤害上下文只覆盖当前原生伤害调用，支持嵌套恢复 */
public final class MaidWorldSoundState {

    private final EntityMaid maid;
    private int playerHurtSoundCount = MaidSoundTuning.WORLD_SOUND_PLAYER_HURT_SOUND_COUNT;
    private int pickupSoundCount = MaidSoundTuning.WORLD_SOUND_PICKUP_SOUND_COUNT;
    private DamageSource damage;

    public MaidWorldSoundState(EntityMaid maid) {
        this.maid = maid;
    }

    public void tick() {
        if (playerHurtSoundCount > 0) playerHurtSoundCount--;
    }

    public DamageSource beginDamage(DamageSource source) {
        var previous = damage;
        damage = source;
        return previous;
    }

    public void endDamage(DamageSource previous) {
        damage = previous;
    }

    private boolean cancelled() {
        return MinecraftForge.EVENT_BUS.post(new MaidPlaySoundEvent(maid));
    }

    public String ambient() {
        if (cancelled()) return null;
        return switch (maid.getTask()) {
            case IDLE, MAINTENANCE_HATCH, CROPSNH_BREEDING -> SoundUtil
                .environmentSound(maid, SoundUtil.name(MaidSoundIds.ID_MODE_IDLE), MaidSoundTuning.HALF_FACTOR_F);
            case ATTACK -> SoundUtil
                .attackSound(maid, SoundUtil.name(MaidSoundIds.ID_MODE_ATTACK), MaidSoundTuning.HALF_FACTOR_F);
            case RANGED_ATTACK -> SoundUtil
                .attackSound(maid, SoundUtil.name(MaidSoundIds.ID_MODE_RANGE_ATTACK), MaidSoundTuning.HALF_FACTOR_F);
        };
    }

    public String hurt() {
        if (cancelled()) return null;
        if (damage != null && damage.isFireDamage()) return SoundUtil.name(MaidSoundIds.ID_AI_HURT_FIRE);
        if (damage != null && damage.getEntity() instanceof EntityPlayer) {
            if (playerHurtSoundCount > 0) return null;
            playerHurtSoundCount = MaidSoundTuning.WORLD_SOUND_PLAYER_HURT_SOUND_COUNT;
            return SoundUtil.name(MaidSoundIds.ID_AI_HURT_PLAYER);
        }
        return SoundUtil.name(MaidSoundIds.ID_AI_HURT);
    }

    public String death() {
        return cancelled() ? null : SoundUtil.name(MaidSoundIds.ID_AI_DEATH);
    }

    public void tryPickup() {
        if (!cancelled() && --pickupSoundCount == 0) {
            maid.playSound(
                SoundUtil.name(MaidSoundIds.ID_AI_ITEM_GET),
                MaidSoundTuning.TARGET_GET_AS_INT_THRESHOLD,
                MaidSoundTuning.TARGET_GET_AS_INT_THRESHOLD);
            pickupSoundCount = MaidSoundTuning.WORLD_SOUND_PICKUP_SOUND_COUNT;
        }
    }
}
