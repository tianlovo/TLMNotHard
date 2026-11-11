package com.tianluoqaq.tlmnh.common.protection.recovery;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

/** 在本模组持有物品的存档边界使用；无法确认身份时原样封存，不返回空槽 */
public final class ProtectedItemCodec {

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
            IntegrationGuard.report(ProtectionRecoveryIds.ID_ITEM_SNAPSHOT_WRITE, failure);
            data = (NBTTagCompound) snapshot.fallback.copy();
        }
        var meta = new NBTTagCompound();
        String name = Item.itemRegistry.getNameForObject(stack.getItem());
        if (!written) data.setShort(ProtectionRecoveryIds.ID_ID, (short) Item.getIdFromItem(stack.getItem()));
        meta.setInteger(
            ProtectionRecoveryIds.KEY_SCHEMA,
            ProtectionRecoveryTuning.PROTECTED_ITEM_SET_INTEGER_SECOND_PARAM);
        meta.setString(ProtectionRecoveryIds.KEY_REGISTRY, name == null ? "" : name);
        meta.setString(ProtectionRecoveryIds.KEY_DEPENDENCY, DependencyFingerprint.item(name == null ? "" : name));
        meta.setInteger(ProtectionRecoveryIds.KEY_EXACT_COUNT, snapshot.count);
        meta.setInteger(ProtectionRecoveryIds.KEY_EXACT_DAMAGE, snapshot.damage);
        meta.setBoolean(ProtectionRecoveryIds.KEY_COMPLETE, snapshot.complete && written);
        data.setTag(ProtectionRecoveryIds.META, meta);
        return data;
    }

    /** 槽位入口封存超限整份，不把其余数量裁掉；正常消耗后的零数量仍按原语义清空 */
    public static ItemStack admit(ItemStack stack, int limit) {
        if (stack == null || stack.stackSize <= 0) return null;
        var raw = encode(stack);
        var decoded = decode(raw);
        if (decoded == null
            || decoded.getItem() == RecoveryParcel.ITEM && !ItemStack.areItemStacksEqual(stack, decoded))
            return decoded == null ? seal(raw, ProtectionRecoveryIds.ID_ADMISSION) : decoded;
        if (stack.stackSize > limit) return seal(raw, ProtectionRecoveryIds.ID_INVENTORY_LIMIT);
        return stack;
    }

    public static ItemStack decode(NBTTagCompound source) {
        if (source == null || source.hasNoTags()) return null;
        try {
            return resolve(source);
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.report(ProtectionRecoveryIds.ID_ITEM_DECODE, failure);
            return seal(source, ProtectionRecoveryIds.ID_DECODE);
        }
    }

    public static ItemStack decodeField(NBTTagCompound parent, String key) {
        if (!parent.hasKey(key)) return null;
        if (parent.hasKey(key, Constants.NBT.TAG_COMPOUND)) return decode(parent.getCompoundTag(key));
        var raw = new NBTTagCompound();
        raw.setTag(
            key,
            parent.getTag(key)
                .copy());
        return seal(raw, ProtectionRecoveryReflectionSpec.SRG_MEMBER_FIELD_TYPE);
    }

    /** 只读预检同样用于玩家显式拆包；不把新模组同数值ID当作原物品 */
    private static ItemStack resolve(NBTTagCompound source) {
        var data = (NBTTagCompound) source.copy();
        if (!data.hasKey(ProtectionRecoveryIds.ID_ID, Constants.NBT.TAG_SHORT)
            || !data.hasKey(ProtectionRecoveryIds.KEY_COUNT, Constants.NBT.TAG_BYTE)
            || !data.hasKey(ProtectionRecoveryIds.KEY_DAMAGE, Constants.NBT.TAG_SHORT)
            || data.hasKey(ProtectionRecoveryIds.ID_TAG)
                && !data.hasKey(ProtectionRecoveryIds.ID_TAG, Constants.NBT.TAG_COMPOUND))
            return seal(source, ProtectionRecoveryIds.ID_ITEM_TYPE);
        if (data.hasKey(ProtectionRecoveryIds.META)
            && !data.hasKey(ProtectionRecoveryIds.META, Constants.NBT.TAG_COMPOUND))
            return seal(source, ProtectionRecoveryIds.ID_SCHEMA);
        var meta = data.getCompoundTag(ProtectionRecoveryIds.META);
        String name = meta.getString(ProtectionRecoveryIds.KEY_REGISTRY);
        int schema = meta.getInteger(ProtectionRecoveryIds.KEY_SCHEMA);
        if (data.hasKey(ProtectionRecoveryIds.META)
            && (!meta.hasKey(ProtectionRecoveryIds.KEY_SCHEMA, Constants.NBT.TAG_INT)
                || schema < ProtectionRecoveryTuning.MIN_SCHEMA_THRESHOLD
                || schema > ProtectionRecoveryTuning.PROTECTED_ITEM_SET_INTEGER_SECOND_PARAM
                || !meta.hasKey(ProtectionRecoveryIds.KEY_REGISTRY, Constants.NBT.TAG_STRING)
                || name.isEmpty()
                || !meta.hasKey(ProtectionRecoveryIds.KEY_DEPENDENCY, Constants.NBT.TAG_STRING)))
            return seal(source, ProtectionRecoveryIds.ID_SCHEMA);
        int count = data.getByte(ProtectionRecoveryIds.KEY_COUNT),
            damage = data.getShort(ProtectionRecoveryIds.KEY_DAMAGE);
        if (schema == ProtectionRecoveryTuning.PROTECTED_ITEM_SET_INTEGER_SECOND_PARAM) {
            if (!meta.hasKey(ProtectionRecoveryIds.KEY_EXACT_COUNT, Constants.NBT.TAG_INT)
                || !meta.hasKey(ProtectionRecoveryIds.KEY_EXACT_DAMAGE, Constants.NBT.TAG_INT)
                || !meta.hasKey(ProtectionRecoveryIds.KEY_COMPLETE, Constants.NBT.TAG_BYTE)
                || !meta.getBoolean(ProtectionRecoveryIds.KEY_COMPLETE))
                return seal(source, ProtectionRecoveryIds.ID_INCOMPLETE_SNAPSHOT);
            count = meta.getInteger(ProtectionRecoveryIds.KEY_EXACT_COUNT);
            damage = meta.getInteger(ProtectionRecoveryIds.KEY_EXACT_DAMAGE);
            if (data.getByte(ProtectionRecoveryIds.KEY_COUNT) != (byte) count
                || data.getShort(ProtectionRecoveryIds.KEY_DAMAGE) != (short) damage)
                return seal(source, ProtectionRecoveryIds.ID_NUMERIC_MISMATCH);
        }
        if (count <= 0 || count > ProtectionRecoveryTuning.MAX_COUNT_THRESHOLD)
            return seal(source, ProtectionRecoveryIds.ID_COUNT);
        if (damage < 0 || damage > Short.MAX_VALUE) return seal(source, ProtectionRecoveryIds.ID_DAMAGE);
        if (name.isEmpty()) {
            String missing = MISSING.get(
                data.getShort(ProtectionRecoveryIds.ID_ID) & ProtectionRecoveryTuning.PROTECTED_ITEM_BIT_MASK_65535);
            if (missing != null) return seal(source, ProtectionRecoveryIds.ID_MISSING + missing);
        } else {
            Object item = Item.itemRegistry.getObject(name);
            if (!(item instanceof Item registered) || !name.equals(Item.itemRegistry.getNameForObject(registered)))
                return seal(source, ProtectionRecoveryIds.ID_MISSING + name);
            String expected = meta.getString(ProtectionRecoveryIds.KEY_DEPENDENCY);
            String current = DependencyFingerprint.item(name);
            if (!expected.equals(current) || current.equals(ProtectionRecoveryIds.ID_ABSENT)
                || current.equals(ProtectionRecoveryIds.ID_UNREADABLE))
                return seal(source, ProtectionRecoveryIds.ID_DEPENDENCY + name);
            data.setShort(ProtectionRecoveryIds.ID_ID, (short) Item.getIdFromItem(registered));
        }
        ItemStack result = ItemStack.loadItemStackFromNBT(data);
        if (result == null || result.getItem() == null) return seal(source, ProtectionRecoveryIds.ID_MISSING_2);
        if (result.stackSize != count
            || result.stackSize > Math.min(ProtectionRecoveryTuning.MAX_COUNT_THRESHOLD, result.getMaxStackSize()))
            return seal(source, ProtectionRecoveryIds.ID_COUNT);
        Integer actualDamage = ItemStackSnapshot.readDamage(result);
        if (actualDamage == null || actualDamage != damage) return seal(source, ProtectionRecoveryIds.ID_DAMAGE);
        return result;
    }

    public static ItemStack seal(NBTTagCompound raw, String reason) {
        IntegrationGuard.report(
            ProtectionRecoveryIds.ID_ITEM + reason,
            new IllegalStateException(ProtectionRecoveryDiagnostics.MSG_ITEM_RETAINED_IN_RECOVERY_PARCEL + reason));
        var stack = new ItemStack(RecoveryParcel.ITEM);
        var payload = new NBTTagCompound();
        payload.setInteger(ProtectionRecoveryIds.KEY_SCHEMA, ProtectionRecoveryTuning.MIN_SCHEMA_THRESHOLD);
        payload.setTag(ProtectionRecoveryIds.KEY_ORIGINAL, raw.copy());
        payload.setString(ProtectionRecoveryIds.KEY_REASON, reason);
        stack.setTagCompound(payload);
        return stack;
    }

    public static ItemStack restore(ItemStack parcel) {
        if (parcel == null || parcel.getItem() != RecoveryParcel.ITEM
            || parcel.stackSize != ProtectionRecoveryTuning.MIN_SCHEMA_THRESHOLD
            || !parcel.hasTagCompound()) return null;
        var payload = parcel.getTagCompound();
        if (payload.getInteger(ProtectionRecoveryIds.KEY_SCHEMA) != ProtectionRecoveryTuning.MIN_SCHEMA_THRESHOLD
            || !payload.hasKey(ProtectionRecoveryIds.KEY_ORIGINAL, Constants.NBT.TAG_COMPOUND)) return null;
        var original = payload.getCompoundTag(ProtectionRecoveryIds.KEY_ORIGINAL);
        // 封存的旧数值ID在新环境可能被另一物品重用，缺少持久注册名时不自动拆包
        if (!original.hasKey(ProtectionRecoveryIds.META, Constants.NBT.TAG_COMPOUND)) return null;
        var resolved = decode(original);
        return resolved == null || resolved.getItem() == RecoveryParcel.ITEM ? null : resolved;
    }
}
