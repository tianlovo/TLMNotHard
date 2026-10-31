package com.tianluoqaq.tlmnh.common.ai;

import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.INpc;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemBow;
import net.minecraftforge.common.util.FakePlayer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 首批战斗安全规则，玩家和宠物遵循上游默认排除语义 */
public final class MaidCombatTargeting {

    public static final double OWNER_RADIUS_SQ = 256;
    private static final int RECENT_ATTACK_TICKS = 100;

    private MaidCombatTargeting() {}

    public static boolean validOwner(EntityMaid maid, EntityLivingBase owner) {
        return owner instanceof EntityPlayer player && !(player instanceof FakePlayer)
            && player.isEntityAlive()
            && player.worldObj == maid.worldObj
            && maid.isOwner(player)
            && maid.worldObj.playerEntities.contains(player)
            && (!(player instanceof EntityPlayerMP serverPlayer) || serverPlayer.theItemInWorldManager.getGameType()
                .getID() != 3);
    }

    public static boolean hasMeleeWeapon(EntityMaid maid) {
        var weapon = maid.getHeldItem();
        return weapon != null && weapon.stackSize > 0
            && !(weapon.getItem() instanceof ItemBow)
            && weapon.getAttributeModifiers()
                .containsKey(SharedMonsterAttributes.attackDamage.getAttributeUnlocalizedName());
    }

    public static boolean canAttack(EntityMaid maid, EntityLivingBase owner, EntityLivingBase target) {
        if (maid.worldObj.isRemote || !maid.isTamed()
            || !maid.isEntityAlive()
            || !maid.getHomeArea()
                .enabled() && !validOwner(maid, owner)
            || target == null
            || target == maid
            || target == owner
            || !target.isEntityAlive()
            || target.isDead
            || target.worldObj != maid.worldObj
            || target.dimension != maid.dimension
            || target.isEntityInvulnerable()
            || maid.worldObj.getEntityByID(target.getEntityId()) != target) return false;
        // 上游默认不攻击任何玩家，因此旁观、无PVP及同队玩家也不会成为目标
        if (target instanceof EntityPlayer || target instanceof EntityTameable
            || target instanceof INpc
            || maid.isOnSameTeam(target)
            || owner != null && owner.isOnSameTeam(target)) return false;
        if (target instanceof EntityLiving mob && mob.hasCustomNameTag()
            && mob.getCustomNameTag()
                .startsWith("MaidNoAttack"))
            return false;
        var area = maid.getHomeArea();
        if (area.enabled()) {
            if (!area.sameDimension() || !area.containsMaid()
                || area.activity() != com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity.WORK
                || !area.contains(target.posX, target.posY, target.posZ)) return false;
        } else if (maid.getDistanceSqToEntity(owner) > OWNER_RADIUS_SQ
            || owner.getDistanceSqToEntity(target) > OWNER_RADIUS_SQ) return false;
        return target instanceof IMob || recentThreat(maid, owner, target);
    }

    public static boolean recentThreat(EntityMaid maid, EntityLivingBase owner, EntityLivingBase target) {
        return target != null && (target == maid.getAITarget() && recent(maid, maid.func_142015_aE())
            || owner != null && (target == owner.getAITarget() && recent(owner, owner.func_142015_aE())
                || target == owner.getLastAttacker() && recent(owner, owner.getLastAttackerTime())));
    }

    private static boolean recent(EntityLivingBase source, int recordedTick) {
        int elapsed = source.ticksExisted - recordedTick;
        return elapsed >= 0 && elapsed <= RECENT_ATTACK_TICKS;
    }

    public static double meleeReachSq(EntityMaid maid, EntityLivingBase target) {
        double width = maid.width * 2.0D;
        return width * width + target.width;
    }
}
