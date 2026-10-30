package com.tianluoqaq.tlmnh.common.proxy;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaid;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidModels;
import com.tianluoqaq.tlmnh.common.inventory.MaidContainerAccess;

import cpw.mods.fml.common.network.IGuiHandler;

/** 公共侧只创建服务端容器，不加载客户端 GUI 类型 */
public class CommonProxy implements IGuiHandler {

    public static final int GUI_MAID = 0;
    public static final int GUI_MAID_MODELS = 1;

    public void init() {}

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int entityId, int unusedY,
        int unusedZ) {
        if ((id != GUI_MAID && id != GUI_MAID_MODELS) || world == null || player == null || world != player.worldObj)
            return null;
        var entity = world.getEntityByID(entityId);
        if (!(entity instanceof EntityMaid maid)) return null;
        if (!MaidContainerAccess.canInteract(player, player, maid)) return null;
        if (id == GUI_MAID_MODELS)
            return player.inventory.getItemStack() == null ? new ContainerMaidModels(player.inventory, maid) : null;
        return new ContainerMaid(player.inventory, maid);
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int entityId, int unusedY,
        int unusedZ) {
        return null;
    }
}
