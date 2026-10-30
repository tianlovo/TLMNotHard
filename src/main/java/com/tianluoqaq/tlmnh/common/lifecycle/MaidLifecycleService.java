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
            || player.getCurrentEquippedItem() != stack
            || player.getDistanceSq(x + 0.5D, y + 1.0D, z + 0.5D) > 64.0D
            || !player.canPlayerEdit(x, y + 1, z, 1, stack)) return Result.DENIED;
        var token = MaidLifecycleItems.token(stack);
        var world = player.worldObj;
        var ledger = MaidLifecycleData.get(world);
        var owner = ledger.owner(token, MaidLifecycleData.Kind.INITIAL);
        if (owner == null) return Result.INVALID_CREDENTIAL;
        if (!owner.equals(player.getUniqueID())) return Result.WRONG_OWNER;
        if (ledger.reserve(token, MaidLifecycleData.Kind.INITIAL) == null) return Result.INVALID_CREDENTIAL;
        boolean committed = false;
        EntityMaid maid = null;
        try {
            maid = new EntityMaid(world);
            if (!maid.bindTo(owner)) return Result.DENIED;
            maid.setLocationAndAngles(x + 0.5D, y + 1.0D, z + 0.5D, 0, 0);
            if (!safeSpawn(maid)) return Result.BLOCKED;
            if (!world.spawnEntityInWorld(maid) || !maid.isEntityAlive() || !MaidSpawnChecks.present(maid, world))
                return Result.SPAWN_REJECTED;
            if (!maid.isTamed() || !owner.toString()
                .equals(maid.func_152113_b())) return Result.DENIED;
            if (!canUse(player) || player.worldObj != maid.worldObj || player.getCurrentEquippedItem() != stack)
                return Result.DENIED;
            ledger.commit(token);
            committed = true;
            // 替换整个槽位，不让1.7.10创造模式的旧栈数量恢复把魂符复制回来
            player.inventory.setInventorySlotContents(
                player.inventory.currentItem,
                new ItemStack(MaidResurrectionContent.EMPTY_SLAB));
            player.inventory.markDirty();
            maid.worldObj.setEntityState(maid, (byte) 7);
            return Result.SUCCESS;
        } finally {
            if (!committed) {
                MaidSpawnChecks.discard(maid);
                ledger.rollback(token);
            }
        }
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
            return Result.HEALTH_LOW;
        var film = shrine.beginUse();
        if (film == null) return Result.INVALID_CREDENTIAL;
        boolean committed = false;
        var world = player.worldObj;
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
            if (!safeSpawn(maid)) return Result.BLOCKED;
            if (!world.spawnEntityInWorld(maid) || !maid.isEntityAlive() || !MaidSpawnChecks.present(maid, world))
                return Result.SPAWN_REJECTED;
            if (!maid.isTamed() || !owner.toString()
                .equals(maid.func_152113_b())) return Result.DENIED;
            if (!canUse(player) || player.worldObj != maid.worldObj
                || shrine.isInvalid()
                || maid.worldObj.getTileEntity(shrine.xCoord, shrine.yCoord, shrine.zCoord) != shrine
                || player.getCurrentEquippedItem() != null
                || !hasHealth(player.getHealth(), player.getMaxHealth(), player.capabilities.isCreativeMode))
                return Result.DENIED;
            ledger.commit(token);
            committed = true;
            if (!player.capabilities.isCreativeMode) player.setHealth(0.25F);
            maid.worldObj.setEntityState(maid, (byte) 7);
            return Result.SUCCESS;
        } finally {
            if (!committed) {
                MaidSpawnChecks.discard(maid);
                if (ledger != null) ledger.rollback(token);
            }
            shrine.finishUse(committed);
        }
    }

    private static boolean safeSpawn(EntityMaid maid) {
        var world = maid.worldObj;
        int x = (int) Math.floor(maid.posX);
        int y = (int) Math.floor(maid.posY);
        int z = (int) Math.floor(maid.posZ);
        return y > 0 && y + 2 < world.getHeight()
            && world.blockExists(x, y + 2, z)
            && World.doesBlockHaveSolidTopSurface(world, x, y - 1, z)
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
