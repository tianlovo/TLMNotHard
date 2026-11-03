package com.tianluoqaq.tlmnh.common.protection;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** 超出槽位、重复槽位保留到独立保管区，不覆盖另一件物品 */
public final class RecoveryStore {

    public static final String TAG = "TLMNHRecovery";
    private final List<ItemStack> items = new ArrayList<>();
    private final com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid;
    private final com.tianluoqaq.tlmnh.common.life.TileEntitySnackCabinet cabinet;

    public RecoveryStore() {
        maid = null;
        cabinet = null;
    }

    public RecoveryStore(com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid) {
        this.maid = java.util.Objects.requireNonNull(maid);
        cabinet = null;
    }

    public RecoveryStore(com.tianluoqaq.tlmnh.common.life.TileEntitySnackCabinet cabinet) {
        maid = null;
        this.cabinet = java.util.Objects.requireNonNull(cabinet);
    }

    public void retain(NBTTagCompound raw) {
        items.add(ProtectedItemCodec.seal(raw, "inventory_slot"));
    }

    public void read(NBTTagList source) {
        items.clear();
        for (int index = 0; index < source.tagCount(); index++) {
            var raw = source.getCompoundTagAt(index);
            ItemStack stack = ProtectedItemCodec.decode(raw);
            items.add(stack == null ? ProtectedItemCodec.seal(raw, "empty_entry") : stack);
        }
    }

    public void retainInvalidList(NBTTagCompound parent, String key) {
        if (parent.hasKey(key) && (!parent.hasKey(key, 9) || parent.getTagList(key, 10)
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

    public void depositAll(net.minecraft.world.World world, double x, double y, double z) {
        throw new IllegalStateException("Source tile removal must use its owned staged return boundary");
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
        if (maid != null) return com.tianluoqaq.tlmnh.common.lifecycle.MaidRecoveryTransfer.recover(this, player, maid);
        if (cabinet != null)
            return com.tianluoqaq.tlmnh.common.lifecycle.MaidRecoveryTransfer.recover(this, player, cabinet);
        IntegrationGuard.report(
            cpw.mods.fml.relauncher.Side.SERVER,
            "player_inventory/recovery",
            new IllegalStateException("Authoritative recovery inventory owner required"),
            player.getUniqueID());
        return false;
    }

    public ItemStack firstForTransfer() {
        return items.isEmpty() ? null : items.get(0);
    }

    public boolean takeFirstForTransfer(ItemStack expected) {
        if (items.isEmpty() || items.get(0) != expected) return false;
        items.remove(0);
        return true;
    }

    public void restoreFirstForTransfer(ItemStack stack, NBTTagList remaining) {
        if (!write().equals(remaining)) throw new IllegalStateException("Unknown recovery contents retained");
        items.add(0, stack.copy());
    }
}
