package com.tianluoqaq.tlmnh.client.proxy;

import java.io.IOException;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityTombstoneRenderer;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.client.development.ClientDevelopmentControl;
import com.tianluoqaq.tlmnh.client.gui.BuiltinModelNames;
import com.tianluoqaq.tlmnh.client.gui.GuiMaid;
import com.tianluoqaq.tlmnh.client.gui.GuiMaidModels;
import com.tianluoqaq.tlmnh.client.gui.GuiMaidNaming;
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
        com.tianluoqaq.tlmnh.client.protection.ClientProtectionEvents.register();
        var worldSounds = com.tianluoqaq.tlmnh.client.sound.MaidWorldSoundPlayer.INSTANCE;
        FMLCommonHandler.instance()
            .bus()
            .register(worldSounds);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(worldSounds);
        var effects = com.tianluoqaq.tlmnh.client.gui.MaidEffectDisplay.INSTANCE;
        FMLCommonHandler.instance()
            .bus()
            .register(effects);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(effects);
        var attacks = com.tianluoqaq.tlmnh.client.gui.MaidAttackDisplay.INSTANCE;
        FMLCommonHandler.instance()
            .bus()
            .register(attacks);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(attacks);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS
            .register(new com.github.tartaricacid.touhoulittlemaid.client.event.MaidSoundFreqEvent());
        com.tianluoqaq.tlmnh.client.gui.MaidClientPreferences.load();
        var manager = Minecraft.getMinecraft()
            .getResourceManager();
        var models = new BuiltinMaidModels(
            location -> manager.getResource(location)
                .getInputStream());
        ((IReloadableResourceManager) manager).registerReloadListener(models);
        ((IReloadableResourceManager) manager).registerReloadListener(BuiltinModelNames.INSTANCE);
        ((IReloadableResourceManager) manager).registerReloadListener(
            com.github.tartaricacid.touhoulittlemaid.client.gui.entity.cache.CacheIconManager.INSTANCE);
        ((IReloadableResourceManager) manager).registerReloadListener(
            new com.github.tartaricacid.touhoulittlemaid.client.resource.listener.EmojiReloadListener());
        RenderingRegistry.registerEntityRenderingHandler(EntityMaid.class, new MaidRenderer(models));
        var tombstoneModels = new com.tianluoqaq.tlmnh.client.lifecycle.TombstoneModelResources();
        ((IReloadableResourceManager) manager).registerReloadListener(tombstoneModels);
        RenderingRegistry
            .registerEntityRenderingHandler(MaidTombstoneEntity.class, new EntityTombstoneRenderer(tombstoneModels));
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
    public void enqueueMaidSound(
        com.github.tartaricacid.touhoulittlemaid.network.message.PlayMaidSoundMessage message) {
        com.tianluoqaq.tlmnh.client.sound.MaidWorldSoundPlayer.INSTANCE.enqueue(message);
    }

    @Override
    public void enqueueMaidEffects(com.github.tartaricacid.touhoulittlemaid.network.message.SendEffectMessage message) {
        com.tianluoqaq.tlmnh.client.gui.MaidEffectDisplay.INSTANCE.enqueue(message);
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int entityId, int unusedY,
        int unusedZ) {
        var container = getServerGuiElement(id, player, world, entityId, unusedY, unusedZ);
        if (container instanceof com.github.tartaricacid.touhoulittlemaid.inventory.container.backpack.EmptyBackpackContainer original)
            return new com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.backpack.EmptyBackpackContainerScreen(
                original);
        if (container instanceof com.tianluoqaq.tlmnh.common.inventory.ContainerMaidSounds sounds)
            return new com.github.tartaricacid.touhoulittlemaid.client.gui.sound.MaidSoundPackGui(sounds);
        if (container instanceof com.github.tartaricacid.touhoulittlemaid.inventory.container.config.MaidConfigContainer config)
            return new com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.config.MaidConfigContainerGui(
                config);
        if (container instanceof com.github.tartaricacid.touhoulittlemaid.inventory.container.task.TaskConfigContainer task)
            return task.attackPage()
                ? new com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.task.AttackTaskConfigGui(task)
                : new com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.task.DefaultMaidTaskConfigGui(
                    task);
        if (container instanceof ContainerMaid maidContainer) return new GuiMaid(maidContainer);
        if (container instanceof ContainerMaidNaming namingContainer) return new GuiMaidNaming(namingContainer);
        if (container instanceof ContainerMaidModels modelContainer) return modelContainer.isOriginalView()
            ? com.github.tartaricacid.touhoulittlemaid.client.gui.entity.cache.CacheIconManager.INSTANCE
                .openMaidModelGui(modelContainer)
            : new GuiMaidModels(modelContainer);
        return null;
    }

    @Override
    public void enqueueMaidAttackSnapshot(com.tianluoqaq.tlmnh.common.network.MaidAttackSnapshot message) {
        com.tianluoqaq.tlmnh.client.gui.MaidAttackDisplay.INSTANCE.enqueue(message);
    }
}
