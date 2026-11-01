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
        while (!items.isEmpty()) {
            com.tianluoqaq.tlmnh.common.lifecycle.MaidFilmRecovery.queueOwnedItem(world, items.get(0), x, y, z);
            items.remove(0);
        }
    }

    public int size() {
        return items.size();
    }

    public void clear() {
        items.clear();
    }

    /** 只向一个空原生背包槽转移一份，满包不删、也不丢到世界 */
    public boolean recoverOne(EntityPlayer player) {
        if (items.isEmpty() || player.worldObj.isRemote) return false;
        for (int slot = 0; slot < player.inventory.mainInventory.length; slot++) {
            if (player.inventory.mainInventory[slot] == null) {
                player.inventory.mainInventory[slot] = items.remove(0);
                player.inventory.markDirty();
                return true;
            }
        }
        return false;
    }
}
