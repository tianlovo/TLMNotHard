package com.tianluoqaq.tlmnh.common.development;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.DamageSource;

import com.github.tartaricacid.touhoulittlemaid.network.message.PlayMaidSoundMessage;
import com.github.tartaricacid.touhoulittlemaid.util.SoundUtil;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.network.MaidSoundNetwork;

/** 只在已确认的虚空/旁观测试场操作原场景女仆，不创建任意实体或路径 */
public final class MaidWorldSoundRuntimeProbe {

    private MaidWorldSoundRuntimeProbe() {}

    public static void run(EntityPlayerMP player, String action) {
        if (!Boolean.getBoolean("tlmnh.devCommands")) throw new IllegalStateException("World sound probe disabled");
        var maid = AnimationLab.ownedSceneMaid(player);
        switch (action) {
            case "prepare" -> {
                maid.setSoundPackId("touhou_little_maid");
                maid.getConfigManager()
                    .setSoundFreq(1);
                maid.setSilent(false);
                maid.setTask(MaidTask.IDLE);
            }
            case "ambient" -> {
                maid.setTask(MaidTask.ATTACK);
                maid.getRNG()
                    .setSeed(0);
                maid.playLivingSound();
            }
            case "credit" -> maid.playSound(SoundUtil.name("credit"), 1, 1);
            case "move" -> maid.setPosition(maid.posX + 2, maid.posY, maid.posZ);
            case "frequency_0" -> maid.getConfigManager()
                .setSoundFreq(0);
            case "frequency_1" -> maid.getConfigManager()
                .setSoundFreq(1);
            case "silent" -> maid.setSilent(true);
            case "unmute" -> maid.setSilent(false);
            case "hurt" -> {
                float health = maid.getHealth();
                if (!maid.attackEntityFrom(DamageSource.outOfWorld, 0.25F))
                    throw new IllegalStateException("Controlled hurt was rejected");
                maid.setHealth(health);
            }
            case "stale_uuid" -> MaidSoundNetwork.send(
                maid,
                new PlayMaidSoundMessage(
                    maid.dimension,
                    maid.getEntityId(),
                    UUID.randomUUID(),
                    maid.getSoundPackId(),
                    "maid.credit"));
            case "stale_dimension" -> MaidSoundNetwork.send(
                maid,
                new PlayMaidSoundMessage(
                    maid.dimension + 1,
                    maid.getEntityId(),
                    maid.getUniqueID(),
                    maid.getSoundPackId(),
                    "maid.credit"));
            case "remove" -> maid.worldObj.removeEntity(maid);
            default -> throw new IllegalArgumentException("Unknown world sound fixture");
        }
        TLMNotHard.LOG.info(
            "TLMNH_WORLD_SOUND_CASE action={} maid={} x={} frequency={} silent={} dimension={}",
            action,
            maid.getUniqueID(),
            maid.posX,
            maid.getConfigManager()
                .getSoundFreq(),
            maid.isSilent(),
            maid.dimension);
    }
}
