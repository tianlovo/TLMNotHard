package com.tianluoqaq.tlmnh.client.proxy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.client.gui.GuiMaid;
import com.tianluoqaq.tlmnh.client.render.BuiltinMaidModels;
import com.tianluoqaq.tlmnh.client.render.MaidRenderer;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaid;
import com.tianluoqaq.tlmnh.common.proxy.CommonProxy;

import cpw.mods.fml.client.registry.RenderingRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class ClientProxy extends CommonProxy {

    @Override
    public void init() {
        var manager = Minecraft.getMinecraft()
            .getResourceManager();
        var models = new BuiltinMaidModels(
            location -> manager.getResource(location)
                .getInputStream());
        ((IReloadableResourceManager) manager).registerReloadListener(models);
        RenderingRegistry.registerEntityRenderingHandler(EntityMaid.class, new MaidRenderer(models));
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int entityId, int unusedY,
        int unusedZ) {
        var container = getServerGuiElement(id, player, world, entityId, unusedY, unusedZ);
        return container instanceof ContainerMaid maidContainer ? new GuiMaid(maidContainer) : null;
    }
}
