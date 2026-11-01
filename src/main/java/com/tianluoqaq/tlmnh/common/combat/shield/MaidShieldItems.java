package com.tianluoqaq.tlmnh.common.combat.shield;

import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;

import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.compat.battlegear2.common.shield.BattlegearShieldAccess;
import com.tianluoqaq.tlmnh.compat.tconstruct.common.tool.TConstructToolData;
import com.tianluoqaq.tlmnh.compat.tinkersdefense.common.shield.TinkersDefenseShields;

/** native 实装盾牌的物品能力；玩家专用事件、动画和箭捕获不在这里冒充可复用 */
public final class MaidShieldItems {

    private MaidShieldItems() {}

    public enum Status {
        READY,
        EMPTY,
        UNSUPPORTED,
        INVALID_TOOL_DATA,
        BROKEN
    }

    public static final class Profile {

        public final Status status;
        public final String itemId;
        public final boolean tinkers;

        private Profile(Status status, String itemId, boolean tinkers) {
            this.status = status;
            this.itemId = itemId;
            this.tinkers = tinkers;
        }

        public boolean isReady() {
            return status == Status.READY;
        }
    }

    public static Profile inspect(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return new Profile(Status.EMPTY, "", false);
        return IntegrationGuard.call(
            ExternalFeature.SHIELDS,
            () -> inspectSafe(stack.copy()),
            () -> new Profile(Status.UNSUPPORTED, itemId(stack), false));
    }

    public static boolean isShield(ItemStack stack) {
        return stack != null && IntegrationGuard
            .call(ExternalFeature.SHIELDS, () -> BattlegearShieldAccess.isShield(stack), () -> false);
    }

    private static Profile inspectSafe(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || stack.getItem() == null)
            return new Profile(Status.EMPTY, "", false);
        String id = itemId(stack);
        boolean tinkers = TinkersDefenseShields.supports(id);
        if (!BattlegearShieldAccess.isShield(stack) || !BattlegearShieldAccess.supports(id) && !tinkers)
            return new Profile(Status.UNSUPPORTED, id, false);
        if (tinkers) {
            var status = TinkersDefenseShields.status(stack, id);
            if (status != Status.READY) return new Profile(status, id, true);
        }
        return new Profile(Status.READY, id, tinkers);
    }

    public static NBTTagCompound toolData(ItemStack stack) {
        return TConstructToolData.read(stack);
    }

    public static String itemId(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return "";
        String name = Item.itemRegistry.getNameForObject(stack.getItem());
        return name == null ? "" : name;
    }

    public static boolean canBlock(ItemStack stack, DamageSource source) {
        return inspect(stack).isReady() && source != null
            && IntegrationGuard.call(
                ExternalFeature.SHIELDS,
                () -> BattlegearShieldAccess.canBlock(stack.copy(), source),
                () -> false);
    }

    public static float blockAngle(ItemStack stack) {
        return IntegrationGuard.call(ExternalFeature.SHIELDS, () -> {
            float value = BattlegearShieldAccess.blockAngle(stack.copy());
            if (!Float.isFinite(value) || value < 0 || value > 360)
                throw new IllegalStateException("Invalid shield block angle");
            return value;
        }, () -> 0F);
    }

    public static float durabilityReduction(ItemStack stack, DamageSource source) {
        return IntegrationGuard.call(ExternalFeature.SHIELDS, () -> {
            float value = BattlegearShieldAccess.reduction(stack.copy(), source);
            if (!Float.isFinite(value) || value < 0) throw new IllegalStateException("Invalid shield damage reduction");
            return value;
        }, () -> 0F);
    }

    /** Forge 的精确工具能力映射，既不猜显示名称，也不把 GT 链锯当成普通斧 */
    public static boolean canDisableShield(ItemStack weapon) {
        return IntegrationGuard.call(ExternalFeature.SHIELDS, () -> canDisableSafe(weapon), () -> false);
    }

    private static boolean canDisableSafe(ItemStack weapon) {
        if (weapon == null || weapon.stackSize <= 0 || weapon.getItem() == null) return false;
        if (weapon.getItem() instanceof ItemAxe) return true;
        var copy = weapon.copy();
        var classes = copy.getItem()
            .getToolClasses(copy);
        if (classes == null || !classes.contains("axe")) return false;
        // 原生匠魂查询会拒绝 Broken；额外拒绝只有空父tag的伪工具
        if (TConstructToolData.isTool(itemId(copy))) return TConstructToolData.usableAxe(copy);
        return true;
    }

}
