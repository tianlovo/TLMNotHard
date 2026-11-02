/*
 * Mechanics adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: item/ItemSmartSlab.java, item/ItemFilm.java, block/BlockShrine.java
 * TartaricAcid contributors, MIT, see licenses/TouhouLittleMaid-MIT.txt
 */
package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.common.util.FakePlayer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.interaction.MaidInteractions;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

/** 物品与方块仅分派请求，出生、归属、生命代价由服务端确认 */
public final class MaidLifecycleService {

    public enum Result {
        SUCCESS,
        DENIED,
        WRONG_OWNER,
        INVALID_CREDENTIAL,
        BLOCKED,
        SPAWN_REJECTED,
        HEALTH_LOW
    }

    private MaidLifecycleService() {}

    public static boolean isPendingRedemption(EntityMaid maid) {
        return MaidRedemptionDelivery.blocks(maid);
    }

    public static boolean canUse(EntityPlayer player) {
        return player != null && !player.worldObj.isRemote
            && !(player instanceof FakePlayer)
            && player.isEntityAlive()
            && MaidInteractions.isMainHand(player)
            && (!(player instanceof EntityPlayerMP server) || server.theItemInWorldManager.getGameType()
                .getID() != 3);
    }

    public static boolean hasHealth(float health, float maximum, boolean creative) {
        return Float.isFinite(health) && Float.isFinite(maximum)
            && maximum > 0
            && (creative || health >= maximum / 2.0F + 1.0F);
    }

    public static Result releaseInitial(EntityPlayer player, ItemStack stack, int x, int y, int z) {
        if (!canUse(player) || stack == null
            || stack.getItem() != MaidResurrectionContent.INITIAL_SLAB
            || player.getCurrentEquippedItem() != stack) return Result.DENIED;
        var world = player.worldObj;
        int slot = player.inventory.currentItem;
        var expected = stack.copy();
        if (!releaseRequestAllowed(player, stack, expected, world, slot, x, y, z)) return Result.DENIED;
        var token = MaidLifecycleItems.token(stack);
        var ledger = MaidLifecycleData.get(world);
        var kind = ledger.initialKind(token);
        var owner = kind == null ? null : ledger.owner(token, kind);
        if (owner == null) return Result.INVALID_CREDENTIAL;
        if (!owner.equals(player.getUniqueID())) return Result.WRONG_OWNER;
        if (!ledger.redemption(token)
            .hasNoTags())
            return MaidRedemptionDelivery.resume(player, ledger, token) ? Result.SUCCESS : Result.INVALID_CREDENTIAL;
        if (ledger.reserve(token, kind) == null) return Result.INVALID_CREDENTIAL;
        boolean handedOff = false;
        EntityMaid maid = null;
        try {
            maid = new EntityMaid(world);
            if (!maid.bindTo(owner)) return Result.DENIED;
            maid.setLocationAndAngles(x + 0.5D, y + 1.0D, z + 0.5D, 0, 0);
            if (!safeSpawn(maid)) return Result.BLOCKED;
            if (!releaseRequestAllowed(player, stack, expected, world, slot, x, y, z)) return Result.DENIED;
            handedOff = true;
            return MaidRedemptionDelivery.publish(player, ledger, token, maid, stack, null, x, y, z);
        } finally {
            if (!handedOff) {
                MaidSpawnChecks.discard(maid);
                ledger.rollback(token);
            }
        }
    }

    static boolean releaseRequestAllowed(EntityPlayer player, ItemStack stack, ItemStack expected, World world,
        int slot, int x, int y, int z) {
        return releaseRequestUnchanged(player, stack, expected, world, slot, x, y, z)
            && player.canPlayerEdit(x, y + 1, z, 1, stack)
            && releaseRequestUnchanged(player, stack, expected, world, slot, x, y, z);
    }

    private static boolean releaseRequestUnchanged(EntityPlayer player, ItemStack stack, ItemStack expected,
        World world, int slot, int x, int y, int z) {
        return canUse(player) && player.worldObj == world
            && player.inventory.currentItem == slot
            && player.getCurrentEquippedItem() == stack
            && ItemStack.areItemStacksEqual(stack, expected)
            && player.getDistanceSq(x + 0.5D, y + 1.0D, z + 0.5D) <= 64.0D;
    }

    /** 先将本次材料转换为专属凭证；放置失败仍保留该凭证，不回造可再次交易的核心 */
    public static Result activateCore(EntityPlayer player, ItemStack stack, int x, int y, int z) {
        if (!canUse(player) || stack == null
            || stack.stackSize != 1
            || stack.getItem() != MaidResurrectionContent.MAID_CORE
            || player.getCurrentEquippedItem() != stack
            || player.getDistanceSq(x + .5, y + 1, z + .5) > 64
            || !com.tianluoqaq.tlmnh.common.protection.IntegrationGuard.available(
                cpw.mods.fml.relauncher.Side.SERVER,
                com.tianluoqaq.tlmnh.common.protection.ExternalFeature.PROGRESSION_RECIPES))
            return Result.DENIED;
        var world = player.worldObj;
        int slot = player.inventory.currentItem;
        var expected = stack.copy();
        if (!releaseRequestAllowed(player, stack, expected, world, slot, x, y, z)
            || !com.tianluoqaq.tlmnh.common.protection.IntegrationGuard.available(
                cpw.mods.fml.relauncher.Side.SERVER,
                com.tianluoqaq.tlmnh.common.protection.ExternalFeature.PROGRESSION_RECIPES))
            return Result.DENIED;
        var bound = MaidAcquisitionDelivery.core(player, stack, slot, true, x, y, z);
        if (bound == null) return Result.DENIED;
        return releaseInitial(player, bound, x, y, z);
    }

    public static Result resurrect(EntityPlayer player, MaidShrineTile shrine) {
        if (!canUse(player) || shrine == null
            || shrine.isInvalid()
            || shrine.getWorldObj() != player.worldObj
            || player.worldObj.getTileEntity(shrine.xCoord, shrine.yCoord, shrine.zCoord) != shrine
            || player.worldObj.getBlock(shrine.xCoord, shrine.yCoord, shrine.zCoord) != MaidResurrectionContent.SHRINE
            || player.getCurrentEquippedItem() != null
            || player.getDistanceSq(shrine.xCoord + 0.5D, shrine.yCoord + 0.5D, shrine.zCoord + 0.5D) > 64.0D)
            return Result.DENIED;
        if (!hasHealth(player.getHealth(), player.getMaxHealth(), player.capabilities.isCreativeMode))
            // 已进入扣费阶段的精确恢复允许当前已扣为0.25，不再要求第二次达到生存门槛
            return resumeShrine(player, shrine) ? Result.SUCCESS : Result.HEALTH_LOW;
        var existing = shrine.getFilm();
        if (existing != null) {
            var existingToken = MaidLifecycleItems.token(existing);
            if (!MaidLifecycleData.get(player.worldObj)
                .redemption(existingToken)
                .hasNoTags())
                return MaidRedemptionDelivery.resume(player, MaidLifecycleData.get(player.worldObj), existingToken)
                    ? Result.SUCCESS
                    : Result.INVALID_CREDENTIAL;
        }
        var film = shrine.beginUse();
        if (film == null) return Result.INVALID_CREDENTIAL;
        boolean handedOff = false;
        var world = player.worldObj;
        int shrineX = shrine.xCoord, shrineY = shrine.yCoord, shrineZ = shrine.zCoord;
        UUID token = null;
        MaidLifecycleData ledger = null;
        EntityMaid maid = null;
        try {
            token = MaidLifecycleItems.token(film);
            ledger = MaidLifecycleData.get(world);
            var owner = ledger.owner(token, MaidLifecycleData.Kind.FILM);
            if (owner == null) return Result.INVALID_CREDENTIAL;
            var identity = ledger.reserve(token, MaidLifecycleData.Kind.FILM);
            if (identity == null) return Result.INVALID_CREDENTIAL;
            maid = new EntityMaid(world);
            // 与原版readAdditionalSaveData一致：新实体UUID，原主人与设置来自胶片身份
            maid.readEntityFromNBT(identity);
            if (!maid.isTamed() || !owner.toString()
                .equals(maid.func_152113_b())) return Result.INVALID_CREDENTIAL;
            maid.setHealth(maid.getMaxHealth());
            maid.setLocationAndAngles(shrine.xCoord + 0.5D, shrine.yCoord + 1.0D, shrine.zCoord + 0.5D, 0, 0);
            if (!safeSpawn(maid, shrine)) return Result.BLOCKED;
            if (!canUse(player) || player.worldObj != world
                || shrine.isInvalid()
                || shrine.getWorldObj() != world
                || shrine.xCoord != shrineX
                || shrine.yCoord != shrineY
                || shrine.zCoord != shrineZ
                || world.getTileEntity(shrineX, shrineY, shrineZ) != shrine
                || world.getBlock(shrineX, shrineY, shrineZ) != MaidResurrectionContent.SHRINE
                || !ItemStack.areItemStacksEqual(shrine.getFilm(), film)
                || player.getCurrentEquippedItem() != null
                || player.getDistanceSq(shrineX + 0.5D, shrineY + 0.5D, shrineZ + 0.5D) > 64.0D
                || !hasHealth(player.getHealth(), player.getMaxHealth(), player.capabilities.isCreativeMode))
                return Result.DENIED;
            handedOff = true;
            return MaidRedemptionDelivery.publish(player, ledger, token, maid, film, shrine, shrineX, shrineY, shrineZ);
        } finally {
            if (!handedOff) {
                MaidSpawnChecks.discard(maid);
                if (ledger != null) ledger.rollback(token);
            }
            shrine.finishUse(false);
        }
    }

    private static boolean resumeShrine(EntityPlayer player, MaidShrineTile shrine) {
        var ledger = MaidLifecycleData.get(player.worldObj);
        var token = MaidLifecycleItems.token(shrine.getFilm());
        return token != null && !ledger.redemption(token)
            .hasNoTags() && MaidRedemptionDelivery.resume(player, ledger, token);
    }

    private static boolean safeSpawn(EntityMaid maid) {
        return safeSpawn(maid, null);
    }

    private static boolean safeSpawn(EntityMaid maid, MaidShrineTile shrine) {
        var world = maid.worldObj;
        int x = (int) Math.floor(maid.posX);
        int y = (int) Math.floor(maid.posY);
        int z = (int) Math.floor(maid.posZ);
        return y > 0 && y + 2 < world.getHeight()
            && world.blockExists(x, y + 2, z)
            && (shrine == null ? World.doesBlockHaveSolidTopSurface(world, x, y - 1, z)
                : !shrine.isInvalid() && shrine.getWorldObj() == world
                    && shrine.xCoord == x
                    && shrine.yCoord == y - 1
                    && shrine.zCoord == z
                    && maid.posX == x + 0.5D
                    && maid.posY == y
                    && maid.posZ == z + 0.5D
                    && world.getTileEntity(x, y - 1, z) == shrine
                    // 自有神龛中心有真实屋顶碰撞，但原三层形状不构成整面UP支撑
                    && world.getBlock(x, y - 1, z) == MaidResurrectionContent.SHRINE)
            && world.getCollidingBoundingBoxes(maid, maid.boundingBox)
                .isEmpty()
            && world.checkNoEntityCollision(maid.boundingBox)
            && !world.isAnyLiquid(maid.boundingBox);
    }

    public static void tell(EntityPlayer player, Result result) {
        if (result != Result.SUCCESS && player != null && !player.worldObj.isRemote) player.addChatMessage(
            new ChatComponentTranslation(
                "message.tlmnh.lifecycle." + result.name()
                    .toLowerCase(java.util.Locale.ROOT)));
    }
}
