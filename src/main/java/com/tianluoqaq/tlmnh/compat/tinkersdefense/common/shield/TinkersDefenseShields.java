package com.tianluoqaq.tlmnh.compat.tinkersdefense.common.shield;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldItems.Status;
import com.tianluoqaq.tlmnh.compat.tconstruct.common.tool.TConstructToolData;

/** 两种实装匠魂盾牌的材料和损伤合同 */
public final class TinkersDefenseShields {

    private static final String ROUND = "tinkersdefense:Round Shield", HEATER = "tinkersdefense:Heater Shield";

    private TinkersDefenseShields() {}

    public static boolean supports(String id) {
        return ROUND.equals(id) || HEATER.equals(id);
    }

    public static Status status(ItemStack stack, String id) {
        var data = TConstructToolData.read(stack);
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
            || HEATER.equals(id) && data.getInteger("Extra") < 0) return Status.INVALID_TOOL_DATA;
        return data.getBoolean("Broken") ? Status.BROKEN : Status.READY;
    }

    private static boolean hasInteger(NBTTagCompound tag, String name) {
        return tag.hasKey(name, 3);
    }
}
