package com.tianluoqaq.tlmnh.common.proxy;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaid;

import cpw.mods.fml.common.network.IGuiHandler;

/** 公共侧只创建服务端容器，不加载客户端 GUI 类型 */
public class CommonProxy implements IGuiHandler {

    public static final int GUI_MAID = 0;

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int entityId, int unusedY,
        int unusedZ) {
        if (id != GUI_MAID || world == null || player == null || world != player.worldObj) return null;
        var entity = world.getEntityByID(entityId);
        if (!(entity instanceof EntityMaid maid)) return null;
        var container = new ContainerMaid(player.inventory, maid);
        return container.canInteractWith(player) ? container : null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int entityId, int unusedY,
        int unusedZ) {
        return null;
    }
}
