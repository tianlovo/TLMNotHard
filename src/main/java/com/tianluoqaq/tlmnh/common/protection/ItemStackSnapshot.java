package com.tianluoqaq.tlmnh.common.protection;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import cpw.mods.fml.common.ObfuscationReflectionHelper;
import cpw.mods.fml.relauncher.ReflectionHelper;

/** 只读原生存储字段，不在持久化时调用外部Item.getDamage */
final class ItemStackSnapshot {

    private static final Field DAMAGE = damageField();
    final int count, damage;
    final boolean complete;
    final NBTTagCompound fallback;

    ItemStackSnapshot(ItemStack stack) {
        count = stack.stackSize;
        Integer rawDamage = readDamage(stack);
        damage = rawDamage == null ? 0 : rawDamage;
        complete = rawDamage != null;
        fallback = new NBTTagCompound();
        fallback.setByte("Count", (byte) count);
        fallback.setShort("Damage", (short) damage);
        if (stack.stackTagCompound != null) fallback.setTag("tag", stack.stackTagCompound.copy());
        if (!complete) {
            var opaque = new NBTTagCompound();
            // 字段布局未知时保留所有可读原始int，后续按具体代码审查迁移，不猜哪一项是damage
            for (Field field : ItemStack.class.getDeclaredFields())
                if (field.getType() == int.class && !Modifier.isStatic(field.getModifiers())) {
                    try {
                        field.setAccessible(true);
                        opaque.setInteger(field.getName(), field.getInt(stack));
                    } catch (Exception | LinkageError failure) {
                        IntegrationGuard.report("item_snapshot/opaque", failure);
                    }
                }
            fallback.setTag("OpaqueIntegerFields", opaque);
        }
    }

    static Integer readDamage(ItemStack stack) {
        if (DAMAGE == null) return null;
        try {
            return DAMAGE.getInt(stack);
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report("item_snapshot/damage", failure);
            return null;
        }
    }

    private static Field damageField() {
        try {
            return ReflectionHelper.findField(
                ItemStack.class,
                ObfuscationReflectionHelper.remapFieldNames(ItemStack.class.getName(), "itemDamage", "field_77991_e"));
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report("item_snapshot/layout", failure);
            return null;
        }
    }
}
