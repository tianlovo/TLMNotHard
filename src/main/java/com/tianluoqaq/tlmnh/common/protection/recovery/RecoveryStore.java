package com.tianluoqaq.tlmnh.common.protection.recovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.life.cabinet.TileEntitySnackCabinet;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidRecoveryTransfer;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

/** 超出槽位、重复槽位保留到独立保管区，不覆盖另一件物品 */
public final class RecoveryStore {

    private final List<ItemStack> items = new ArrayList<>();
    private final EntityMaid maid;
    private final TileEntitySnackCabinet cabinet;

    public RecoveryStore() {
        maid = null;
        cabinet = null;
    }

    public RecoveryStore(EntityMaid maid) {
        this.maid = Objects.requireNonNull(maid);
        cabinet = null;
    }

    public RecoveryStore(TileEntitySnackCabinet cabinet) {
        maid = null;
        this.cabinet = Objects.requireNonNull(cabinet);
    }

    public void retain(NBTTagCompound raw) {
        items.add(ProtectedItemCodec.seal(raw, ProtectionRecoveryIds.ID_INVENTORY_SLOT));
    }

    public void read(NBTTagList source) {
        items.clear();
        for (int index = 0; index < source.tagCount(); index++) {
            var raw = source.getCompoundTagAt(index);
            ItemStack stack = ProtectedItemCodec.decode(raw);
            items.add(stack == null ? ProtectedItemCodec.seal(raw, ProtectionRecoveryIds.ID_EMPTY_ENTRY) : stack);
        }
    }

    public void retainInvalidList(NBTTagCompound parent, String key) {
        if (parent.hasKey(key)
            && (!parent.hasKey(key, Constants.NBT.TAG_LIST) || parent.getTagList(key, Constants.NBT.TAG_COMPOUND)
                .tagCount() != ((NBTTagList) parent.getTag(key)).tagCount())) {
            var raw = new NBTTagCompound();
            raw.setTag(
                key,
                parent.getTag(key)
                    .copy());
            retain(raw);
        }
    }

    public NBTTagList write() {
        var data = new NBTTagList();
        for (var item : items) data.appendTag(ProtectedItemCodec.encode(item));
        return data;
    }

    public void depositAll(World world, double x, double y, double z) {
        throw new IllegalStateException(ProtectionRecoveryDiagnostics.MSG_SOURCE_TILE_REMOVAL_MUST_USE);
    }

    public int size() {
        return items.size();
    }

    public void clear() {
        items.clear();
    }

    /** 只向一个空原生背包槽转移一份，满包不删、也不丢到世界 */
    public boolean recoverOne(EntityPlayer player) {
        if (player == null || items.isEmpty() || player.worldObj.isRemote) return false;
        if (maid != null) return MaidRecoveryTransfer.recover(this, player, maid);
        if (cabinet != null) return MaidRecoveryTransfer.recover(this, player, cabinet);
        IntegrationGuard.report(
            Side.SERVER,
            ProtectionRecoveryIds.ID_PLAYER_INVENTORY_RECOVERY,
            new IllegalStateException(
                ProtectionRecoveryDiagnostics.MSG_AUTHORITATIVE_RECOVERY_INVENTORY_OWNER_REQUIRED),
            player.getUniqueID());
        return false;
    }

    public ItemStack firstForTransfer() {
        return items.isEmpty() ? null : items.get(ProtectionRecoveryTuning.ITEM_STACK_ITEM_STACK_SNAPSHOT_ZERO_BASE);
    }

    public boolean takeFirstForTransfer(ItemStack expected) {
        if (items.isEmpty() || items.get(ProtectionRecoveryTuning.ITEM_STACK_ITEM_STACK_SNAPSHOT_ZERO_BASE) != expected)
            return false;
        items.remove(ProtectionRecoveryTuning.ITEM_STACK_ITEM_STACK_SNAPSHOT_ZERO_BASE);
        return true;
    }

    public void restoreFirstForTransfer(ItemStack stack, NBTTagList remaining) {
        if (!write().equals(remaining))
            throw new IllegalStateException(ProtectionRecoveryDiagnostics.MSG_UNKNOWN_RECOVERY_CONTENTS_RETAINED);
        items.add(ProtectionRecoveryTuning.ITEM_STACK_ITEM_STACK_SNAPSHOT_ZERO_BASE, stack.copy());
    }
}
