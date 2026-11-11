package com.tianluoqaq.tlmnh.compat.tconstruct.common.tool;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

/** 原生匠魂工具NBT合同与事件身份，业务层不自行解释InfiTool */
public final class TConstructToolData {

    private TConstructToolData() {}

    public static NBTTagCompound read(ItemStack stack) {
        if (stack == null || !stack.hasTagCompound()
            || !stack.getTagCompound()
                .hasKey(TConstructToolNbtKeys.KEY_INFI_TOOL, Constants.NBT.TAG_COMPOUND))
            return null;
        return stack.getTagCompound()
            .getCompoundTag(TConstructToolNbtKeys.KEY_INFI_TOOL);
    }

    public static boolean isTool(String id) {
        return id.startsWith(TConstructToolIds.KEY_T_CONSTRUCT);
    }

    public static boolean usableAxe(ItemStack stack) {
        var data = read(stack);
        return data != null && data.hasKey(TConstructToolNbtKeys.KEY_BROKEN, Constants.NBT.TAG_BYTE)
            && !data.getBoolean(TConstructToolNbtKeys.KEY_BROKEN)
            && data.hasKey(TConstructToolNbtKeys.KEY_TOTAL_DURABILITY, Constants.NBT.TAG_INT)
            && data.getInteger(TConstructToolNbtKeys.KEY_TOTAL_DURABILITY) > 0;
    }

    public static boolean broken(ItemStack stack) {
        var data = read(stack);
        return data != null && data.getBoolean(TConstructToolNbtKeys.KEY_BROKEN);
    }

    public static boolean isToolBuildEvent(Object event) {
        return event.getClass()
            .getName()
            .equals(TConstructToolReflectionSpec.TARGET_CLASS_TOOL_BUILD_EVENT);
    }
}
