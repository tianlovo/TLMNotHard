package com.tianluoqaq.tlmnh.compat.battlegear2.common.shield;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;

import cpw.mods.fml.common.registry.GameRegistry;

/** 提供方物品、音效与盾牌接口；外部类型保持在惰性API类中 */
public final class BattlegearShieldAccess {

    public static final String BLOCK_SOUND = "battlegear2:shield";
    private static final Set<String> IDS = Collections.unmodifiableSet(
        new HashSet<>(
            Arrays.asList(
                "battlegear2:shield.wood",
                "battlegear2:shield.hide",
                "battlegear2:shield.iron",
                "battlegear2:shield.diamond",
                "battlegear2:shield.gold")));

    private BattlegearShieldAccess() {}

    public static boolean supports(String id) {
        return IDS.contains(id);
    }

    public static Item woodenShieldItem() {
        return GameRegistry.findItem("battlegear2", "shield.wood");
    }

    public static boolean isShield(ItemStack stack) {
        return Api.isShield(stack);
    }

    public static boolean canBlock(ItemStack stack, DamageSource source) {
        return Api.canBlock(stack, source);
    }

    public static float blockAngle(ItemStack stack) {
        return Api.blockAngle(stack);
    }

    public static float reduction(ItemStack stack, DamageSource source) {
        return Api.reduction(stack, source);
    }

    public static boolean supportsArrowCapture(ItemStack stack) {
        return Api.supportsArrowCapture(stack);
    }

    /** 在脱离真实库存的副本上核对已审阅提供方的计数协议，不调用玩家专用catchArrow */
    public static ArrowCapture prepareArrowCapture(ItemStack stack) {
        return Api.prepareArrowCapture(stack);
    }

    public static final class ArrowCapture {

        private final NBTBase previous;
        private final short next;

        private ArrowCapture(ItemStack stack, int next) {
            previous = stack.hasTagCompound() && stack.getTagCompound()
                .hasKey("arrows") ? stack.getTagCompound()
                    .getTag("arrows")
                    .copy() : null;
            this.next = (short) next;
        }

        /** 只合并已核计数，不覆盖磨损回调刚写入的其他NBT或重放外部回调 */
        public NBTTagCompound applyTo(ItemStack stack) {
            var data = stack.hasTagCompound() ? stack.getTagCompound() : null;
            if (!Objects.equals(previous, data == null ? null : data.getTag("arrows"))) return null;
            var updated = data == null ? new NBTTagCompound() : (NBTTagCompound) data.copy();
            updated.setShort("arrows", next);
            return updated;
        }
    }

    public static Class<?> apiType() {
        return Api.type();
    }

    private static final class Api {

        static boolean isShield(ItemStack stack) {
            return stack.getItem() instanceof mods.battlegear2.api.shield.IShield;
        }

        static boolean canBlock(ItemStack stack, DamageSource source) {
            return ((mods.battlegear2.api.shield.IShield) stack.getItem()).canBlock(stack, source);
        }

        static float blockAngle(ItemStack stack) {
            return ((mods.battlegear2.api.shield.IShield) stack.getItem()).getBlockAngle(stack);
        }

        static float reduction(ItemStack stack, DamageSource source) {
            return ((mods.battlegear2.api.shield.IShield) stack.getItem()).getDamageReduction(stack, source);
        }

        static boolean supportsArrowCapture(ItemStack stack) {
            return stack != null && stack.getItem() instanceof mods.battlegear2.api.shield.IArrowCatcher
                && stack.getItem() instanceof mods.battlegear2.api.shield.IArrowDisplay;
        }

        static ArrowCapture prepareArrowCapture(ItemStack stack) {
            if (!supportsArrowCapture(stack)) return null;
            var original = stack.copy();
            var changed = stack.copy();
            if (original.hasTagCompound() && original.getTagCompound()
                .hasKey("arrows")
                && !original.getTagCompound()
                    .hasKey("arrows", 2))
                throw new IllegalStateException("Unknown shield arrow-count encoding");
            var display = (mods.battlegear2.api.shield.IArrowDisplay) changed.getItem();
            int previous = display.getArrowCount(changed);
            if (previous < 0 || previous > Short.MAX_VALUE)
                throw new IllegalStateException("Invalid shield arrow count");
            int next = Math.min(Short.MAX_VALUE, previous + 1);
            display.setArrowCount(changed, previous + 1);
            var expected = original.copy();
            if (!expected.hasTagCompound()) expected.setTagCompound(new NBTTagCompound());
            expected.getTagCompound()
                .setShort("arrows", (short) next);
            if (!ItemStack.areItemStacksEqual(expected, changed))
                throw new IllegalStateException("Shield arrow callback changed data outside its count");
            return new ArrowCapture(original, next);
        }

        static Class<?> type() {
            return mods.battlegear2.api.shield.IShield.class;
        }
    }
}
