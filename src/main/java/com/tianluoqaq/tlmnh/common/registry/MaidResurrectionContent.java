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
    public static final Item EMPTY_SLAB = new MaidLifecycleItems.StoredMaidItem()
        .setMaxStackSize(ContentRegistryTuning.SET_MAX_STACK_SIZE_SINGLE_UNIT)
        .setUnlocalizedName(ContentRegistryIds.ID_TLMNH_SMART_SLAB_EMPTY)
        .setTextureName(ContentRegistryIds.ID_TOUHOU_LITTLE_MAID_SMART_SLAB_EMPTY)
        .setCreativeTab(CreativeTabs.tabMisc);
    public static final Item FILM = new MaidFilmItem()
        .setMaxStackSize(ContentRegistryTuning.SET_MAX_STACK_SIZE_SINGLE_UNIT)
        .setUnlocalizedName(ContentRegistryIds.ID_TLMNH_FILM)
        .setTextureName(ContentRegistryIds.ID_TOUHOU_LITTLE_MAID_FILM)
        .setCreativeTab(CreativeTabs.tabMisc);
    public static final MaidShrineBlock SHRINE = new MaidShrineBlock();
    private static boolean registered;

    private MaidResurrectionContent() {}

    public static void register() {
        if (registered) throw new IllegalStateException(
            ContentRegistryDiagnostics.MSG_MAID_RESURRECTION_CONTENT_ALREADY_REGISTERED);
        registered = true;
        GameRegistry.registerItem(INITIAL_SLAB, ContentRegistryIds.ID_SMART_SLAB_INIT);
        GameRegistry.registerItem(FILLED_SLAB, ContentRegistryIds.ID_SMART_SLAB_HAS_MAID);
        GameRegistry.registerItem(MAID_CORE, ContentRegistryIds.ID_MAID_CORE);
        GameRegistry.registerItem(EMPTY_SLAB, ContentRegistryIds.ID_SMART_SLAB_EMPTY);
        GameRegistry.registerItem(FILM, ContentRegistryIds.ID_FILM);
        SHRINE.setCreativeTab(CreativeTabs.tabDecorations);
        GameRegistry.registerBlock(SHRINE, ContentRegistryIds.ID_SHRINE);
        GameRegistry.registerTileEntity(MaidShrineTile.class, ContentRegistryIds.ID_TLMNH_SHRINE);
        EntityRegistry.registerModEntity(
            MaidTombstoneEntity.class,
            ContentRegistryIds.ID_TOMBSTONE,
            ContentRegistryTuning.SET_MAX_STACK_SIZE_SINGLE_UNIT,
            ModContext.modInstance(),
            ContentRegistryTuning.RESURRECTION_CONTENT_REGISTER_MOD_ENTITY_FIFTH_PARAM,
            ContentRegistryTuning.RESURRECTION_CONTENT_REGISTER_MOD_ENTITY_SIXTH_PARAM,
            false);
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
            Class.forName(
                ContentRegistryReflectionSpec.TARGET_CLASS_SPAWNER_ANIMALS,
                true,
                MaidResurrectionContent.class.getClassLoader());
            if (!MixinCompatibilityPlugin.hasNaturalWorldGenHook()) throw new IllegalStateException(
                ContentRegistryDiagnostics.MSG_NATIVE_WORLDGEN_DIMENSION_GATE_UNAVAILABLE);
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, ContentRegistryIds.ID_ACQUISITION_NATURAL_WORLDGEN, failure);
            return;
        }
        var biomes = MaidAcquisitionDiagnostics.resolveBiomes(MaidAcquisitionConfig.naturalBiomes);
        if (biomes.length == 0) return;
        EntityRegistry.addSpawn(
            EntityMaid.class,
            MaidAcquisitionConfig.naturalWeight,
            ContentRegistryTuning.SET_MAX_STACK_SIZE_SINGLE_UNIT,
            ContentRegistryTuning.SET_MAX_STACK_SIZE_SINGLE_UNIT,
            EnumCreatureType.creature,
            biomes);
        ModContext.LOG.info(
            ContentRegistryDiagnostics.MSG_NATURAL_MAID_SPAWNING_REGISTERED_USABLEBIOMES,
            biomes.length,
            Arrays.toString(MaidAcquisitionConfig.naturalBiomes),
            MaidAcquisitionConfig.naturalWeight,
            Arrays.toString(MaidAcquisitionConfig.naturalDimensions));
    }
}
