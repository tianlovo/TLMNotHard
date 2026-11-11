package com.tianluoqaq.tlmnh.compat.tinkersdefense.common.shield;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldItems.Status;
import com.tianluoqaq.tlmnh.compat.tconstruct.common.tool.TConstructToolData;

/** 两种实装匠魂盾牌的材料和损伤合同 */
public final class TinkersDefenseShields {



    private TinkersDefenseShields() {}

    public static boolean supports(String id) {
        return TinkersDefenseShieldIds.ROUND.equals(id) || TinkersDefenseShieldIds.HEATER.equals(id);
    }

    public static Status status(ItemStack stack, String id) {
        var data = TConstructToolData.read(stack);
        if (data == null || !hasInteger(data, TinkersDefenseShieldIds.KEY_HEAD)
            || !hasInteger(data, TinkersDefenseShieldIds.KEY_HANDLE)
            || !hasInteger(data, TinkersDefenseShieldIds.KEY_ACCESSORY)
            || TinkersDefenseShieldIds.HEATER.equals(id) && !hasInteger(data, TinkersDefenseShieldIds.KEY_EXTRA)
            || !hasInteger(data, TinkersDefenseShieldIds.KEY_TOTAL_DURABILITY)
            || !hasInteger(data, TinkersDefenseShieldIds.KEY_DAMAGE)
            || !data.hasKey(TinkersDefenseShieldIds.KEY_BROKEN, Constants.NBT.TAG_BYTE)
            || data.getInteger(TinkersDefenseShieldIds.KEY_TOTAL_DURABILITY) <= 0
            || data.getInteger(TinkersDefenseShieldIds.KEY_DAMAGE) < 0
            || data.getInteger(TinkersDefenseShieldIds.KEY_HEAD) < 0
            || data.getInteger(TinkersDefenseShieldIds.KEY_HANDLE) < 0
            || data.getInteger(TinkersDefenseShieldIds.KEY_ACCESSORY) < 0
            || TinkersDefenseShieldIds.HEATER.equals(id) && data.getInteger(TinkersDefenseShieldIds.KEY_EXTRA) < 0) return Status.INVALID_TOOL_DATA;
        return data.getBoolean(TinkersDefenseShieldIds.KEY_BROKEN) ? Status.BROKEN : Status.READY;
    }

    private static boolean hasInteger(NBTTagCompound tag, String name) {
        return tag.hasKey(name, Constants.NBT.TAG_INT);
    }
}
