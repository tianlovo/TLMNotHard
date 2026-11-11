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
import net.minecraftforge.common.util.Constants;

import com.tianluoqaq.tlmnh.common.combat.spi.ShieldCompatProvider;
import com.tianluoqaq.tlmnh.compat.battlegear2.Battlegear2Compat;

import cpw.mods.fml.common.registry.GameRegistry;

/** 提供方物品、音效与盾牌接口；外部类型保持在惰性API类中 */
public final class BattlegearShieldAccess {

    private static final Set<String> IDS = Collections.unmodifiableSet(
        new HashSet<>(
            Arrays.asList(
                BattlegearShieldIds.ID_BATTLEGEAR2_SHIELD_WOOD,
                BattlegearShieldIds.ID_BATTLEGEAR2_SHIELD_HIDE,
                BattlegearShieldIds.ID_BATTLEGEAR2_SHIELD_IRON,
                BattlegearShieldIds.ID_BATTLEGEAR2_SHIELD_DIAMOND,
                BattlegearShieldIds.ID_BATTLEGEAR2_SHIELD_GOLD)));

    private BattlegearShieldAccess() {}

    public static boolean supports(String id) {
        return IDS.contains(id);
    }

    public static Item woodenShieldItem() {
        return GameRegistry.findItem(Battlegear2Compat.MOD_ID, BattlegearShieldIds.ID_SHIELD_WOOD);
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

    public static final class ArrowCapture implements ShieldCompatProvider.ArrowCaptureHandle {

        private final NBTBase previous;
        private final short next;

        private ArrowCapture(ItemStack stack, int next) {
            previous = stack.hasTagCompound() && stack.getTagCompound()
                .hasKey(BattlegearShieldIds.ID_ARROWS) ? stack.getTagCompound()
                    .getTag(BattlegearShieldIds.ID_ARROWS)
                    .copy() : null;
            this.next = (short) next;
        }

        /** 只合并已核计数，不覆盖磨损回调刚写入的其他NBT或重放外部回调 */
        @Override
        public NBTTagCompound applyTo(ItemStack stack) {
            var data = stack.hasTagCompound() ? stack.getTagCompound() : null;
            if (!Objects.equals(previous, data == null ? null : data.getTag(BattlegearShieldIds.ID_ARROWS)))
                return null;
            var updated = data == null ? new NBTTagCompound() : (NBTTagCompound) data.copy();
            updated.setShort(BattlegearShieldIds.ID_ARROWS, next);
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
                .hasKey(BattlegearShieldIds.ID_ARROWS)
                && !original.getTagCompound()
                    .hasKey(BattlegearShieldIds.ID_ARROWS, Constants.NBT.TAG_SHORT))
                throw new IllegalStateException(BattlegearShieldDiagnostics.MSG_UNKNOWN_SHIELD_ARROW_COUNT_ENCODING);
            var display = (mods.battlegear2.api.shield.IArrowDisplay) changed.getItem();
            int previous = display.getArrowCount(changed);
            if (previous < 0 || previous > Short.MAX_VALUE)
                throw new IllegalStateException(BattlegearShieldDiagnostics.MSG_INVALID_SHIELD_ARROW_COUNT);
            int next = Math.min(Short.MAX_VALUE, previous + 1);
            display.setArrowCount(changed, previous + 1);
            var expected = original.copy();
            if (!expected.hasTagCompound()) expected.setTagCompound(new NBTTagCompound());
            expected.getTagCompound()
                .setShort(BattlegearShieldIds.ID_ARROWS, (short) next);
            if (!ItemStack.areItemStacksEqual(expected, changed))
                throw new IllegalStateException(BattlegearShieldDiagnostics.MSG_SHIELD_ARROW_CALLBACK_CHANGED_DATA);
            return new ArrowCapture(original, next);
        }

        static Class<?> type() {
            return mods.battlegear2.api.shield.IShield.class;
        }
    }
}
