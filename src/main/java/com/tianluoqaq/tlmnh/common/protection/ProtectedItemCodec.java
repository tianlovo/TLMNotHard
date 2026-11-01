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
        var snapshot = new ItemStackSnapshot(stack);
        NBTTagCompound data;
        boolean written = false;
        try {
            // 原生写出可能复用stackTagCompound，必须脱离活物品的可变引用
            data = (NBTTagCompound) stack.writeToNBT(new NBTTagCompound())
                .copy();
            written = true;
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.report("item_snapshot/write", failure);
            data = (NBTTagCompound) snapshot.fallback.copy();
        }
        var meta = new NBTTagCompound();
        String name = Item.itemRegistry.getNameForObject(stack.getItem());
        if (!written) data.setShort("id", (short) Item.getIdFromItem(stack.getItem()));
        meta.setInteger("Schema", 2);
        meta.setString("Registry", name == null ? "" : name);
        meta.setString("Dependency", DependencyFingerprint.item(name == null ? "" : name));
        meta.setInteger("ExactCount", snapshot.count);
        meta.setInteger("ExactDamage", snapshot.damage);
        meta.setBoolean("Complete", snapshot.complete && written);
        data.setTag(META, meta);
        return data;
    }

    /** 槽位入口封存超限整份，不把其余数量裁掉；正常消耗后的零数量仍按原语义清空 */
    public static ItemStack admit(ItemStack stack, int limit) {
        if (stack == null || stack.stackSize <= 0) return null;
        var raw = encode(stack);
        var decoded = decode(raw);
        if (decoded == null
            || decoded.getItem() == RecoveryParcel.ITEM && !ItemStack.areItemStacksEqual(stack, decoded))
            return decoded == null ? seal(raw, "admission") : decoded;
        if (stack.stackSize > limit) return seal(raw, "inventory_limit");
        return stack;
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
        if (!data.hasKey("id", 2) || !data.hasKey("Count", 1)
            || !data.hasKey("Damage", 2)
            || data.hasKey("tag") && !data.hasKey("tag", 10)) return seal(source, "item_type");
        if (data.hasKey(META) && !data.hasKey(META, 10)) return seal(source, "schema");
        var meta = data.getCompoundTag(META);
        String name = meta.getString("Registry");
        int schema = meta.getInteger("Schema");
        if (data.hasKey(META) && (!meta.hasKey("Schema", 3) || schema < 1
            || schema > 2
            || !meta.hasKey("Registry", 8)
            || name.isEmpty()
            || !meta.hasKey("Dependency", 8))) return seal(source, "schema");
        int count = data.getByte("Count"), damage = data.getShort("Damage");
        if (schema == 2) {
            if (!meta.hasKey("ExactCount", 3) || !meta.hasKey("ExactDamage", 3)
                || !meta.hasKey("Complete", 1)
                || !meta.getBoolean("Complete")) return seal(source, "incomplete_snapshot");
            count = meta.getInteger("ExactCount");
            damage = meta.getInteger("ExactDamage");
            if (data.getByte("Count") != (byte) count || data.getShort("Damage") != (short) damage)
                return seal(source, "numeric_mismatch");
        }
        if (count <= 0 || count > 64) return seal(source, "count");
        if (damage < 0 || damage > Short.MAX_VALUE) return seal(source, "damage");
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
        if (result.stackSize != count || result.stackSize > Math.min(64, result.getMaxStackSize()))
            return seal(source, "count");
        Integer actualDamage = ItemStackSnapshot.readDamage(result);
        if (actualDamage == null || actualDamage != damage) return seal(source, "damage");
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
