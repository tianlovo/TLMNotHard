package com.tianluoqaq.tlmnh.common.combat.shield;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;

import mods.battlegear2.api.shield.IShield;

/** native 实装盾牌的物品能力；玩家专用事件、动画和箭捕获不在这里冒充可复用 */
public final class MaidShieldItems {

    private static final Set<String> BG_IDS = Collections.unmodifiableSet(
        new HashSet<>(
            Arrays.asList(
                "battlegear2:shield.wood",
                "battlegear2:shield.hide",
                "battlegear2:shield.iron",
                "battlegear2:shield.diamond",
                "battlegear2:shield.gold")));
    private static final String ROUND = "tinkersdefense:Round Shield", HEATER = "tinkersdefense:Heater Shield";

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
        if (stack == null || stack.stackSize <= 0 || stack.getItem() == null)
            return new Profile(Status.EMPTY, "", false);
        String id = itemId(stack);
        boolean tinkers = ROUND.equals(id) || HEATER.equals(id);
        if (!(stack.getItem() instanceof IShield) || !BG_IDS.contains(id) && !tinkers)
            return new Profile(Status.UNSUPPORTED, id, false);
        if (tinkers) {
            var data = toolData(stack);
            if (data == null || !hasInteger(data, "Head")
                || !hasInteger(data, "Handle")
                || !hasInteger(data, "Accessory")
                || HEATER.equals(id) && !hasInteger(data, "Extra")
                || !hasInteger(data, "TotalDurability")
                || !hasInteger(data, "Damage")
                || !data.hasKey("Broken", 1)
                || data.getInteger("TotalDurability") <= 0
                || data.getInteger("Damage") < 0
                || data.getInteger("Head") < 0
                || data.getInteger("Handle") < 0
                || data.getInteger("Accessory") < 0
                || HEATER.equals(id) && data.getInteger("Extra") < 0)
                return new Profile(Status.INVALID_TOOL_DATA, id, true);
            if (data.getBoolean("Broken")) return new Profile(Status.BROKEN, id, true);
        }
        return new Profile(Status.READY, id, tinkers);
    }

    private static boolean hasInteger(NBTTagCompound tag, String name) {
        return tag.hasKey(name, 3);
    }

    public static NBTTagCompound toolData(ItemStack stack) {
        if (stack == null || !stack.hasTagCompound()
            || !stack.getTagCompound()
                .hasKey("InfiTool", 10))
            return null;
        return stack.getTagCompound()
            .getCompoundTag("InfiTool");
    }

    public static String itemId(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return "";
        String name = Item.itemRegistry.getNameForObject(stack.getItem());
        return name == null ? "" : name;
    }

    public static boolean canBlock(ItemStack stack, DamageSource source) {
        return inspect(stack).isReady() && source != null && ((IShield) stack.getItem()).canBlock(stack, source);
    }

    public static float blockAngle(ItemStack stack) {
        return ((IShield) stack.getItem()).getBlockAngle(stack);
    }

    public static float durabilityReduction(ItemStack stack, DamageSource source) {
        return ((IShield) stack.getItem()).getDamageReduction(stack, source);
    }

    /** Forge 的精确工具能力映射，既不猜显示名称，也不把 GT 链锯当成普通斧 */
    public static boolean canDisableShield(ItemStack weapon) {
        if (weapon == null || weapon.stackSize <= 0 || weapon.getItem() == null) return false;
        if (weapon.getItem() instanceof ItemAxe) return true;
        var copy = weapon.copy();
        var classes = copy.getItem()
            .getToolClasses(copy);
        if (classes == null || !classes.contains("axe")) return false;
        // 原生匠魂查询会拒绝 Broken；额外拒绝只有空父tag的伪工具
        if (itemId(copy).startsWith("TConstruct:")) {
            var data = toolData(copy);
            return data != null && data.hasKey("Broken", 1)
                && !data.getBoolean("Broken")
                && hasInteger(data, "TotalDurability")
                && data.getInteger("TotalDurability") > 0;
        }
        return true;
    }
}
