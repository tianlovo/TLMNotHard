package com.tianluoqaq.tlmnh.common.registry;

import java.util.Arrays;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.item.Item;
import net.minecraftforge.common.MinecraftForge;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig;
import com.tianluoqaq.tlmnh.common.config.MaidAcquisitionDiagnostics;
import com.tianluoqaq.tlmnh.common.foundation.ModContext;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleEvents;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineBlock;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineTile;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity;
import com.tianluoqaq.tlmnh.common.lifecycle.item.MaidCoreItem;
import com.tianluoqaq.tlmnh.common.lifecycle.item.MaidFilmItem;
import com.tianluoqaq.tlmnh.common.lifecycle.item.MaidLifecycleItems;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.core.MixinCompatibilityPlugin;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.registry.EntityRegistry;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.Side;

/** 获取与复活内容注册；蒸汽核心及LV神龛配方由进度层在postInit注册 */
public final class MaidResurrectionContent {

    public static final Item INITIAL_SLAB = new MaidLifecycleItems.InitialSlab().setCreativeTab(CreativeTabs.tabMisc);
    public static final Item FILLED_SLAB = new MaidLifecycleItems.FilledSlab().setCreativeTab(CreativeTabs.tabMisc);
    public static final Item MAID_CORE = new MaidCoreItem().setCreativeTab(CreativeTabs.tabMisc);
    public static final Item EMPTY_SLAB = new MaidLifecycleItems.StoredMaidItem().setMaxStackSize(1)
        .setUnlocalizedName("tlmnh.smart_slab_empty")
        .setTextureName("touhou_little_maid:smart_slab_empty")
        .setCreativeTab(CreativeTabs.tabMisc);
    public static final Item FILM = new MaidFilmItem().setMaxStackSize(1)
        .setUnlocalizedName("tlmnh.film")
        .setTextureName("touhou_little_maid:film")
        .setCreativeTab(CreativeTabs.tabMisc);
    public static final MaidShrineBlock SHRINE = new MaidShrineBlock();
    private static boolean registered;

    private MaidResurrectionContent() {}

    public static void register() {
        if (registered) throw new IllegalStateException("Maid resurrection content already registered");
        registered = true;
        GameRegistry.registerItem(INITIAL_SLAB, "smart_slab_init");
        GameRegistry.registerItem(FILLED_SLAB, "smart_slab_has_maid");
        GameRegistry.registerItem(MAID_CORE, "maid_core");
        GameRegistry.registerItem(EMPTY_SLAB, "smart_slab_empty");
        GameRegistry.registerItem(FILM, "film");
        SHRINE.setCreativeTab(CreativeTabs.tabDecorations);
        GameRegistry.registerBlock(SHRINE, "shrine");
        GameRegistry.registerTileEntity(MaidShrineTile.class, "tlmnh:shrine");
        EntityRegistry
            .registerModEntity(MaidTombstoneEntity.class, "tombstone", 1, ModContext.modInstance(), 80, 10, false);
        var events = new MaidLifecycleEvents();
        MinecraftForge.EVENT_BUS.register(events);
        FMLCommonHandler.instance()
            .bus()
            .register(events);
    }

    public static void registerSpawns() {
        if (!MaidAcquisitionConfig.naturalSpawning) return;
        try {
            // Load the native class before inspecting the post-apply hook result.
            Class.forName("net.minecraft.world.SpawnerAnimals", true, MaidResurrectionContent.class.getClassLoader());
            if (!MixinCompatibilityPlugin.hasNaturalWorldGenHook())
                throw new IllegalStateException("Native worldgen dimension gate unavailable");
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, "acquisition/natural_worldgen", failure);
            return;
        }
        var biomes = MaidAcquisitionDiagnostics.resolveBiomes(MaidAcquisitionConfig.naturalBiomes);
        if (biomes.length == 0) return;
        EntityRegistry
            .addSpawn(EntityMaid.class, MaidAcquisitionConfig.naturalWeight, 1, 1, EnumCreatureType.creature, biomes);
        ModContext.LOG.info(
            "Natural maid spawning registered: usableBiomes={} configuredBiomes={} weight={} dimensions={}",
            biomes.length,
            Arrays.toString(MaidAcquisitionConfig.naturalBiomes),
            MaidAcquisitionConfig.naturalWeight,
            Arrays.toString(MaidAcquisitionConfig.naturalDimensions));
    }
}
