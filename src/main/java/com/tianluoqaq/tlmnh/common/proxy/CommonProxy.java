package com.tianluoqaq.tlmnh.common.proxy;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.backpack.EmptyBackpackContainer;
import com.tianluoqaq.tlmnh.common.interaction.MaidInteractions;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaid;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidModels;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidNaming;
import com.tianluoqaq.tlmnh.common.inventory.MaidContainerAccess;
import com.tianluoqaq.tlmnh.common.network.MaidNamingNetwork;

import cpw.mods.fml.common.network.IGuiHandler;

/** 公共侧只创建服务端容器，不加载客户端 GUI 类型 */
public class CommonProxy implements IGuiHandler {

    public static final int GUI_MAID = 0;
    public static final int GUI_MAID_MODELS = 1;
    public static final int GUI_MAID_NAMING = 2;
    public static final int GUI_MAID_ORIGINAL = 3;
    public static final int GUI_MAID_ORIGINAL_MODELS = 4;
    public static final int GUI_MAID_SOUNDS = 5;

    public void init() {
        MaidNamingNetwork.init();
    }

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int entityId, int unusedY,
        int unusedZ) {
        if ((id != GUI_MAID && id != GUI_MAID_MODELS
            && id != GUI_MAID_NAMING
            && id != GUI_MAID_ORIGINAL
            && id != GUI_MAID_ORIGINAL_MODELS
            && id != GUI_MAID_SOUNDS) || world == null || player == null || world != player.worldObj) return null;
        var entity = world.getEntityByID(entityId);
        if (!(entity instanceof EntityMaid maid)) return null;
        if (!MaidContainerAccess.canInteract(player, player, maid)) return null;
        if (id == GUI_MAID_SOUNDS)
            return Boolean.getBoolean("tlmnh.devCommands") && player.inventory.getItemStack() == null
                ? new com.tianluoqaq.tlmnh.common.inventory.ContainerMaidSounds(player.inventory, maid)
                : null;
        if (id == GUI_MAID_ORIGINAL)
            return Boolean.getBoolean("tlmnh.devCommands") ? new EmptyBackpackContainer(player.inventory, maid) : null;
        if (id == GUI_MAID_ORIGINAL_MODELS)
            return Boolean.getBoolean("tlmnh.devCommands") && player.inventory.getItemStack() == null
                ? new ContainerMaidModels(player.inventory, maid, true)
                : null;
        if (id == GUI_MAID_NAMING) {
            long session = ((long) unusedY << 32) | (unusedZ & 0xFFFFFFFFL);
            if (session == 0 || !MaidInteractions.canInteract(maid, player)
                || !MaidInteractions.isMainHand(player)
                || player.inventory.getItemStack() != null
                || !ContainerMaidNaming.isUnwrittenTag(player.getCurrentEquippedItem())) return null;
            return new ContainerMaidNaming(player.inventory, maid, session);
        }
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
