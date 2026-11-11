package com.tianluoqaq.tlmnh.client.proxy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;

import com.github.tartaricacid.touhoulittlemaid.client.event.MaidSoundFreqEvent;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.cache.CacheIconManager;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.backpack.EmptyBackpackContainerScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.config.MaidConfigContainerGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.task.AttackTaskConfigGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.task.DefaultMaidTaskConfigGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.sound.MaidSoundPackGui;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityTombstoneRenderer;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.tileentity.TileEntityMaidBedRenderer;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.tileentity.TileEntityShrineRenderer;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.tileentity.TileEntitySnackCabinetRenderer;
import com.github.tartaricacid.touhoulittlemaid.client.resource.listener.EmojiReloadListener;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.backpack.EmptyBackpackContainer;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.config.MaidConfigContainer;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.task.TaskConfigContainer;
import com.github.tartaricacid.touhoulittlemaid.network.message.PlayMaidSoundMessage;
import com.github.tartaricacid.touhoulittlemaid.network.message.SendEffectMessage;
import com.tianluoqaq.tlmnh.client.compat.ClientCompatServices;
import com.tianluoqaq.tlmnh.client.gui.BuiltinModelNames;
import com.tianluoqaq.tlmnh.client.gui.GuiMaid;
import com.tianluoqaq.tlmnh.client.gui.GuiMaidModels;
import com.tianluoqaq.tlmnh.client.gui.GuiMaidNaming;
import com.tianluoqaq.tlmnh.client.gui.MaidAttackDisplay;
import com.tianluoqaq.tlmnh.client.gui.MaidClientPreferences;
import com.tianluoqaq.tlmnh.client.gui.MaidEffectDisplay;
import com.tianluoqaq.tlmnh.client.gui.overlay.MaidInteractionTipsOverlay;
import com.tianluoqaq.tlmnh.client.life.bed.MaidBedModelResources;
import com.tianluoqaq.tlmnh.client.life.cabinet.MaidCabinetModelResources;
import com.tianluoqaq.tlmnh.client.lifecycle.TombstoneModelResources;
import com.tianluoqaq.tlmnh.client.lifecycle.shrine.ShrineModelResources;
import com.tianluoqaq.tlmnh.client.protection.ClientProtectionEvents;
import com.tianluoqaq.tlmnh.client.render.BuiltinMaidModels;
import com.tianluoqaq.tlmnh.client.render.MaidBannerModelResources;
import com.tianluoqaq.tlmnh.client.render.MaidPlayerCarryArms;
import com.tianluoqaq.tlmnh.client.render.MaidRenderer;
import com.tianluoqaq.tlmnh.client.render.MaidSwimRenderPose;
import com.tianluoqaq.tlmnh.client.sound.MaidWorldSoundPlayer;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaid;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidModels;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidNaming;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidSounds;
import com.tianluoqaq.tlmnh.common.life.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.life.TileEntitySnackCabinet;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineTile;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity;
import com.tianluoqaq.tlmnh.common.network.MaidAttackSnapshot;
import com.tianluoqaq.tlmnh.common.proxy.CommonProxy;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.client.registry.RenderingRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class ClientProxy extends CommonProxy {

    @Override
    public void init() {
        super.init();
        ClientCompatServices.initAndFreeze();
        ClientProtectionEvents.register();
        MaidSwimRenderPose.register();
        MaidPlayerCarryArms.register();
        var worldSounds = MaidWorldSoundPlayer.INSTANCE;
        FMLCommonHandler.instance()
            .bus()
            .register(worldSounds);
        MinecraftForge.EVENT_BUS.register(worldSounds);
        var effects = MaidEffectDisplay.INSTANCE;
        FMLCommonHandler.instance()
            .bus()
            .register(effects);
        MinecraftForge.EVENT_BUS.register(effects);
        var attacks = MaidAttackDisplay.INSTANCE;
        FMLCommonHandler.instance()
            .bus()
            .register(attacks);
        MinecraftForge.EVENT_BUS.register(attacks);
        MinecraftForge.EVENT_BUS.register(new MaidSoundFreqEvent());
        MaidClientPreferences.load();
        MaidInteractionTipsOverlay.register();
        var manager = Minecraft.getMinecraft()
            .getResourceManager();
        var models = new BuiltinMaidModels(
            location -> manager.getResource(location)
                .getInputStream());
        ((IReloadableResourceManager) manager).registerReloadListener(models);
        ((IReloadableResourceManager) manager).registerReloadListener(BuiltinModelNames.INSTANCE);
        ((IReloadableResourceManager) manager).registerReloadListener(MaidBannerModelResources.INSTANCE);
        ((IReloadableResourceManager) manager).registerReloadListener(CacheIconManager.INSTANCE);
        ((IReloadableResourceManager) manager).registerReloadListener(new EmojiReloadListener());
        RenderingRegistry.registerEntityRenderingHandler(EntityMaid.class, new MaidRenderer(models));
        var tombstoneModels = new TombstoneModelResources();
        ((IReloadableResourceManager) manager).registerReloadListener(tombstoneModels);
        RenderingRegistry
            .registerEntityRenderingHandler(MaidTombstoneEntity.class, new EntityTombstoneRenderer(tombstoneModels));
        var bedModels = new MaidBedModelResources();
        ((IReloadableResourceManager) manager).registerReloadListener(bedModels);
        ClientRegistry.bindTileEntitySpecialRenderer(TileEntityMaidBed.class, new TileEntityMaidBedRenderer(bedModels));
        var cabinetModels = new MaidCabinetModelResources();
        ((IReloadableResourceManager) manager).registerReloadListener(cabinetModels);
        ClientRegistry.bindTileEntitySpecialRenderer(
            TileEntitySnackCabinet.class,
            new TileEntitySnackCabinetRenderer(cabinetModels));
        var shrineModels = new ShrineModelResources();
        ((IReloadableResourceManager) manager).registerReloadListener(shrineModels);
        ClientRegistry.bindTileEntitySpecialRenderer(MaidShrineTile.class, new TileEntityShrineRenderer(shrineModels));
    }

    @Override
    public void enqueueMaidSound(PlayMaidSoundMessage message) {
        MaidWorldSoundPlayer.INSTANCE.enqueue(message);
    }

    @Override
    public void enqueueMaidEffects(SendEffectMessage message) {
        MaidEffectDisplay.INSTANCE.enqueue(message);
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int entityId, int unusedY,
        int unusedZ) {
        var container = getServerGuiElement(id, player, world, entityId, unusedY, unusedZ);
        if (container instanceof EmptyBackpackContainer original) return new EmptyBackpackContainerScreen(original);
        if (container instanceof ContainerMaidSounds sounds) return new MaidSoundPackGui(sounds);
        if (container instanceof MaidConfigContainer config) return new MaidConfigContainerGui(config);
        if (container instanceof TaskConfigContainer task)
            return task.attackPage() ? new AttackTaskConfigGui(task) : new DefaultMaidTaskConfigGui(task);
        if (container instanceof ContainerMaid maidContainer) return new GuiMaid(maidContainer);
        if (container instanceof ContainerMaidNaming namingContainer) return new GuiMaidNaming(namingContainer);
        if (container instanceof ContainerMaidModels modelContainer)
            return modelContainer.isOriginalView() ? CacheIconManager.INSTANCE.openMaidModelGui(modelContainer)
                : new GuiMaidModels(modelContainer);
        return null;
    }

    @Override
    public void enqueueMaidAttackSnapshot(MaidAttackSnapshot message) {
        MaidAttackDisplay.INSTANCE.enqueue(message);
    }
}
