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
                .hasKey("InfiTool", Constants.NBT.TAG_COMPOUND))
            return null;
        return stack.getTagCompound()
            .getCompoundTag("InfiTool");
    }

    public static boolean isTool(String id) {
        return id.startsWith("TConstruct:");
    }

    public static boolean usableAxe(ItemStack stack) {
        var data = read(stack);
        return data != null && data.hasKey("Broken", Constants.NBT.TAG_BYTE)
            && !data.getBoolean("Broken")
            && data.hasKey("TotalDurability", Constants.NBT.TAG_INT)
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
