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
    private int playerHurtSoundCount = 120;
    private int pickupSoundCount = 5;
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
            case IDLE -> SoundUtil.environmentSound(maid, SoundUtil.name("mode.idle"), 0.5F);
            case ATTACK -> SoundUtil.attackSound(maid, SoundUtil.name("mode.attack"), 0.5F);
            case RANGED_ATTACK -> SoundUtil.attackSound(maid, SoundUtil.name("mode.range_attack"), 0.5F);
        };
    }

    public String hurt() {
        if (cancelled()) return null;
        if (damage != null && damage.isFireDamage()) return SoundUtil.name("ai.hurt_fire");
        if (damage != null && damage.getEntity() instanceof EntityPlayer) {
            if (playerHurtSoundCount > 0) return null;
            playerHurtSoundCount = 120;
            return SoundUtil.name("ai.hurt_player");
        }
        return SoundUtil.name("ai.hurt");
    }

    public String death() {
        return cancelled() ? null : SoundUtil.name("ai.death");
    }

    public void tryPickup() {
        if (!cancelled() && --pickupSoundCount == 0) {
            maid.playSound(SoundUtil.name("ai.item_get"), 1, 1);
            pickupSoundCount = 5;
        }
    }
}
