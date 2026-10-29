package com.tianluoqaq.tlmnh.client.proxy;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.client.gui.GuiMaid;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaid;
import com.tianluoqaq.tlmnh.common.proxy.CommonProxy;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class ClientProxy extends CommonProxy {

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int entityId, int unusedY,
        int unusedZ) {
        var container = getServerGuiElement(id, player, world, entityId, unusedY, unusedZ);
        return container instanceof ContainerMaid maidContainer ? new GuiMaid(maidContainer) : null;
    }
}
