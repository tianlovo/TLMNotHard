package com.tianluoqaq.tlmnh.compat.tconstruct.common.tool;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** 原生匠魂工具NBT合同与事件身份，业务层不自行解释InfiTool */
public final class TConstructToolData {

    private TConstructToolData() {}

    public static NBTTagCompound read(ItemStack stack) {
        if (stack == null || !stack.hasTagCompound()
            || !stack.getTagCompound()
                .hasKey("InfiTool", 10))
            return null;
        return stack.getTagCompound()
            .getCompoundTag("InfiTool");
    }

    public static boolean isTool(String id) {
        return id.startsWith("TConstruct:");
    }

    public static boolean usableAxe(ItemStack stack) {
        var data = read(stack);
        return data != null && data.hasKey("Broken", 1)
            && !data.getBoolean("Broken")
            && data.hasKey("TotalDurability", 3)
            && data.getInteger("TotalDurability") > 0;
    }

    public static boolean broken(ItemStack stack) {
        var data = read(stack);
        return data != null && data.getBoolean("Broken");
    }

    public static boolean isToolBuildEvent(Object event) {
        return event.getClass()
            .getName()
            .equals("tconstruct.library.event.ToolBuildEvent");
    }
}
