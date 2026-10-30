package com.tianluoqaq.tlmnh.client.proxy;

import java.io.IOException;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.client.development.ClientDevelopmentControl;
import com.tianluoqaq.tlmnh.client.gui.BuiltinModelNames;
import com.tianluoqaq.tlmnh.client.gui.GuiMaid;
import com.tianluoqaq.tlmnh.client.gui.GuiMaidModels;
import com.tianluoqaq.tlmnh.client.gui.GuiMaidNaming;
import com.tianluoqaq.tlmnh.client.lifecycle.TombstoneRenderer;
import com.tianluoqaq.tlmnh.client.render.BuiltinMaidModels;
import com.tianluoqaq.tlmnh.client.render.MaidRenderer;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaid;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidModels;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidNaming;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity;
import com.tianluoqaq.tlmnh.common.proxy.CommonProxy;

import cpw.mods.fml.client.registry.RenderingRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class ClientProxy extends CommonProxy {

    @Override
    public void init() {
        super.init();
        var manager = Minecraft.getMinecraft()
            .getResourceManager();
        var models = new BuiltinMaidModels(
            location -> manager.getResource(location)
                .getInputStream());
        ((IReloadableResourceManager) manager).registerReloadListener(models);
        ((IReloadableResourceManager) manager).registerReloadListener(BuiltinModelNames.INSTANCE);
        RenderingRegistry.registerEntityRenderingHandler(EntityMaid.class, new MaidRenderer(models));
        RenderingRegistry.registerEntityRenderingHandler(MaidTombstoneEntity.class, new TombstoneRenderer());
        if (Boolean.getBoolean("tlmnh.devCommands")) {
            try {
                FMLCommonHandler.instance()
                    .bus()
                    .register(new ClientDevelopmentControl(Minecraft.getMinecraft()));
            } catch (IOException error) {
                throw new IllegalStateException("Cannot initialize client development controls", error);
            }
        }
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int entityId, int unusedY,
        int unusedZ) {
        var container = getServerGuiElement(id, player, world, entityId, unusedY, unusedZ);
        if (container instanceof ContainerMaid maidContainer) return new GuiMaid(maidContainer);
        if (container instanceof ContainerMaidNaming namingContainer) return new GuiMaidNaming(namingContainer);
        return container instanceof ContainerMaidModels modelContainer ? new GuiMaidModels(modelContainer) : null;
    }
}
