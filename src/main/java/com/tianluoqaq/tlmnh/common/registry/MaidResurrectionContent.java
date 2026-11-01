package com.tianluoqaq.tlmnh.common.registry;

import java.util.ArrayList;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.item.Item;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.MinecraftForge;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidCoreItem;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleEvents;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleItems;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineBlock;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineTile;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.registry.EntityRegistry;
import cpw.mods.fml.common.registry.GameRegistry;

/** 获取与复活内容注册；蒸汽核心及LV神龛配方由进度层在postInit注册 */
public final class MaidResurrectionContent {

    public static final Item INITIAL_SLAB = new MaidLifecycleItems.InitialSlab().setCreativeTab(CreativeTabs.tabMisc);
    public static final Item MAID_CORE = new MaidCoreItem().setCreativeTab(CreativeTabs.tabMisc);
    public static final Item EMPTY_SLAB = new MaidLifecycleItems.StoredMaidItem().setMaxStackSize(1)
        .setUnlocalizedName("tlmnh.smart_slab_empty")
        .setTextureName("touhou_little_maid:smart_slab_empty")
        .setCreativeTab(CreativeTabs.tabMisc);
    public static final Item FILM = new MaidLifecycleItems.StoredMaidItem().setMaxStackSize(1)
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
        GameRegistry.registerItem(MAID_CORE, "maid_core");
        GameRegistry.registerItem(EMPTY_SLAB, "smart_slab_empty");
        GameRegistry.registerItem(FILM, "film");
        SHRINE.setCreativeTab(CreativeTabs.tabDecorations);
        GameRegistry.registerBlock(SHRINE, "shrine");
        GameRegistry.registerTileEntity(MaidShrineTile.class, "tlmnh:shrine");
        EntityRegistry.registerModEntity(MaidTombstoneEntity.class, "tombstone", 1, TLMNotHard.INSTANCE, 80, 10, false);
        var events = new MaidLifecycleEvents();
        MinecraftForge.EVENT_BUS.register(events);
        FMLCommonHandler.instance()
            .bus()
            .register(events);
    }

    public static void registerSpawns() {
        if (!MaidAcquisitionConfig.naturalSpawning) return;
        var biomes = new ArrayList<BiomeGenBase>();
        for (int id : MaidAcquisitionConfig.naturalBiomes) {
            if (id >= 0 && id < BiomeGenBase.getBiomeGenArray().length) {
                var biome = BiomeGenBase.getBiomeGenArray()[id];
                if (biome != null && !biomes.contains(biome)) biomes.add(biome);
            }
        }
        EntityRegistry.addSpawn(
            EntityMaid.class,
            MaidAcquisitionConfig.naturalWeight,
            1,
            1,
            EnumCreatureType.creature,
            biomes.toArray(new BiomeGenBase[0]));
    }
}
