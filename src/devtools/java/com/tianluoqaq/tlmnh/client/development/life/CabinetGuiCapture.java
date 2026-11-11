package com.tianluoqaq.tlmnh.client.development.life;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.inventory.ContainerChest;

import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 只准入自有保护展台的当前原生零食柜窗口，不能用模型界面捕获入口假称原生容器 */
@SideOnly(Side.CLIENT)
public final class CabinetGuiCapture {

    private CabinetGuiCapture() {}

    public static ContainerChest requireScreen(Minecraft minecraft) {
        var player = minecraft.thePlayer;
        if (!DevelopmentFlags.isDevCommandsEnabled() || !AnimationLab.isLab(minecraft.theWorld)
            || player == null
            || player.getHealth() <= 0
            || !player.capabilities.disableDamage
            || !player.capabilities.isFlying
            || !player.noClip) throw new IllegalStateException("Safe lab observer required");
        if (!(minecraft.currentScreen instanceof GuiChest screen)
            || !(player.openContainer instanceof ContainerChest chest)
            || screen.inventorySlots != chest
            || !chest.canInteractWith(player)
            || player.inventory.getItemStack() != null
            || chest.getLowerChestInventory()
                .getSizeInventory() != 27
            || !chest.getLowerChestInventory()
                .getInventoryName()
                .equals("container.tlmnh.snack_cabinet"))
            throw new IllegalStateException("Current native 27-slot cabinet GUI required");
        return chest;
    }

    public static JsonObject observe(Minecraft minecraft) {
        var chest = requireScreen(minecraft);
        var result = new JsonObject();
        result.addProperty("windowId", chest.windowId);
        result.addProperty(
            "lowerSlots",
            chest.getLowerChestInventory()
                .getSizeInventory());
        result.addProperty(
            "inventoryTitle",
            chest.getLowerChestInventory()
                .getInventoryName());
        result.addProperty(
            "screen",
            minecraft.currentScreen.getClass()
                .getName());
        result.addProperty(
            "container",
            chest.getClass()
                .getName());
        return result;
    }
}
