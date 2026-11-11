package com.tianluoqaq.tlmnh.common.combat.projectile;

import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.server.MinecraftServer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.config.MaidCombatConfig;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

/** 命中前鲜读友方关系与服务端PvP；失败按射手隔离，后续不再消耗弓箭 */
public final class MaidProjectileImpactProtection {

    private MaidProjectileImpactProtection() {}

    private static Side side(EntityMaid maid) {
        return maid.worldObj != null && maid.worldObj.isRemote ? Side.CLIENT : Side.SERVER;
    }

    private static String key(EntityMaid maid) {
        return "projectile_impact/" + maid.getUniqueID();
    }

    public static boolean available(EntityMaid maid) {
        return maid != null && maid.worldObj != null && IntegrationGuard.available(side(maid), key(maid));
    }

    /** 在任何出生回调、火焰效果或扣料之前拒绝缺失的碰撞能力 */
    public static boolean admit(EntityMaid maid, EntityArrow arrow) {
        if (!available(maid)) return false;
        if (arrow instanceof MaidArrowImpactState) return MaidProjectileSource.record(arrow, maid);
        failure(maid, new IllegalStateException("Native arrow impact interception unavailable; release refused"));
        return false;
    }

    private static UUID owner(EntityTameable tameable) {
        String value = tameable.func_152113_b();
        return value == null || value.isEmpty() ? null : UUID.fromString(value);
    }

    public static boolean ignore(EntityMaid maid, Entity victim) {
        if (!available(maid)) return true;
        try {
            if (victim == maid) return true;
            var owner = owner(maid);
            if (owner != null && (owner.equals(victim.getUniqueID())
                || victim instanceof EntityTameable tameable && owner.equals(owner(tameable)))) return true;
            if (victim instanceof EntityLivingBase living && maid.isOnSameTeam(living)) return true;
            if (victim instanceof EntityPlayer && !maid.worldObj.isRemote) {
                // 女仆不是玩家，原EntityPlayerMP的PvP检查不会识别这种箭的主人
                // 必须在原生碰撞开始点燃/伤害前鲜读规则，不能缓存发射时的许可
                var server = MinecraftServer.getServer();
                if (server == null) throw new IllegalStateException("Actual server PvP rule is unavailable");
                if (!server.isPVPEnabled()) return true;
            }
            var id = MaidCombatEntities.id(victim);
            return id != null && MaidCombatConfig.rangedIgnored(id.toString());
        } catch (Exception | LinkageError | AssertionError failure) {
            failure(maid, failure);
            return true;
        }
    }

    public static void missedImpact(EntityMaid maid) {
        failure(
            maid,
            new IllegalStateException(
                "Protected projectile reached the damage boundary; native impact interception unavailable or impact permission changed"));
    }

    static void failure(EntityMaid maid, Throwable cause) {
        UUID recipient = null;
        try {
            recipient = owner(maid);
        } catch (Exception | LinkageError | AssertionError unreadableOwner) {
            IntegrationGuard.throwIfFatal(unreadableOwner);
        }
        IntegrationGuard.report(side(maid), key(maid), cause, recipient);
    }
}
