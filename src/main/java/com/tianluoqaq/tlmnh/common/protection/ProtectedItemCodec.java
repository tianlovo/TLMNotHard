package com.tianluoqaq.tlmnh.common.protection;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** 在本模组持有物品的存档边界使用；无法确认身份时原样封存，不返回空槽 */
public final class ProtectedItemCodec {

    private static final String META = "TLMNHProtection";
    private static final Map<Integer, String> MISSING = new HashMap<>();

    private ProtectedItemCodec() {}

    public static void missing(int id, String name) {
        MISSING.put(id, name);
    }

    public static NBTTagCompound encode(ItemStack stack) {
        var data = stack.writeToNBT(new NBTTagCompound());
        var meta = new NBTTagCompound();
        String name = Item.itemRegistry.getNameForObject(stack.getItem());
        meta.setInteger("Schema", 1);
        meta.setString("Registry", name == null ? "" : name);
        meta.setString("Dependency", DependencyFingerprint.item(name == null ? "" : name));
        data.setTag(META, meta);
        return data;
    }

    public static ItemStack decode(NBTTagCompound source) {
        if (source == null || source.hasNoTags()) return null;
        try {
            return resolve(source);
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.report("item_decode", failure);
            return seal(source, "decode");
        }
    }

    public static ItemStack decodeField(NBTTagCompound parent, String key) {
        if (!parent.hasKey(key)) return null;
        if (parent.hasKey(key, 10)) return decode(parent.getCompoundTag(key));
        var raw = new NBTTagCompound();
        raw.setTag(
            key,
            parent.getTag(key)
                .copy());
        return seal(raw, "field_type");
    }

    /** 只读预检同样用于玩家显式拆包；不把新模组同数值ID当作原物品 */
    private static ItemStack resolve(NBTTagCompound source) {
        var data = (NBTTagCompound) source.copy();
        if (!data.hasKey("id", 99) || !data.hasKey("Count", 99)
            || !data.hasKey("Damage", 99)
            || data.hasKey("tag") && !data.hasKey("tag", 10)) return seal(source, "item_type");
        if (data.hasKey(META) && !data.hasKey(META, 10)) return seal(source, "schema");
        var meta = data.getCompoundTag(META);
        String name = meta.getString("Registry");
        if (data.hasKey(META) && (meta.getInteger("Schema") != 1 || !meta.hasKey("Registry", 8)
            || name.isEmpty()
            || !meta.hasKey("Dependency", 8))) return seal(source, "schema");
        if (name.isEmpty()) {
            String missing = MISSING.get(data.getShort("id") & 65535);
            if (missing != null) return seal(source, "missing:" + missing);
        } else {
            Object item = Item.itemRegistry.getObject(name);
            if (!(item instanceof Item registered) || !name.equals(Item.itemRegistry.getNameForObject(registered)))
                return seal(source, "missing:" + name);
            String expected = meta.getString("Dependency");
            String current = DependencyFingerprint.item(name);
            if (!expected.equals(current) || current.equals("absent") || current.equals("unreadable"))
                return seal(source, "dependency:" + name);
            data.setShort("id", (short) Item.getIdFromItem(registered));
        }
        ItemStack result = ItemStack.loadItemStackFromNBT(data);
        if (result == null || result.getItem() == null) return seal(source, "missing");
        if (result.stackSize <= 0 || result.stackSize > Math.min(64, result.getMaxStackSize()))
            return seal(source, "count");
        return result;
    }

    public static ItemStack seal(NBTTagCompound raw, String reason) {
        IntegrationGuard
            .report("item/" + reason, new IllegalStateException("Item retained in recovery parcel: " + reason));
        var stack = new ItemStack(RecoveryParcel.ITEM);
        var payload = new NBTTagCompound();
        payload.setInteger("Schema", 1);
        payload.setTag("Original", raw.copy());
        payload.setString("Reason", reason);
        stack.setTagCompound(payload);
        return stack;
    }

    public static ItemStack restore(ItemStack parcel) {
        if (parcel == null || parcel.getItem() != RecoveryParcel.ITEM
            || parcel.stackSize != 1
            || !parcel.hasTagCompound()) return null;
        var payload = parcel.getTagCompound();
        if (payload.getInteger("Schema") != 1 || !payload.hasKey("Original", 10)) return null;
        var original = payload.getCompoundTag("Original");
        // 封存的旧数值ID在新环境可能被另一物品重用，缺少持久注册名时不自动拆包
        if (!original.hasKey(META, 10)) return null;
        var resolved = decode(original);
        return resolved == null || resolved.getItem() == RecoveryParcel.ITEM ? null : resolved;
    }
}
