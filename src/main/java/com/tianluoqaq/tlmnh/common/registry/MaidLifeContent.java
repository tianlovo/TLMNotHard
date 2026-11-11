package com.tianluoqaq.tlmnh.common.registry;

import net.minecraft.item.ItemBlock;

import com.tianluoqaq.tlmnh.common.life.bed.BlockMaidBed;
import com.tianluoqaq.tlmnh.common.life.bed.ItemMaidBed;
import com.tianluoqaq.tlmnh.common.life.bed.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.life.cabinet.BlockSnackCabinet;
import com.tianluoqaq.tlmnh.common.life.cabinet.ItemSnackCabinet;
import com.tianluoqaq.tlmnh.common.life.cabinet.TileEntitySnackCabinet;

import cpw.mods.fml.common.registry.GameRegistry;

/** 首版生活核心注册；蒸汽材料配方由进度层统一注册 */
public final class MaidLifeContent {

    public static final BlockSnackCabinet snackCabinet = new BlockSnackCabinet();
    public static final BlockMaidBed maidBed = new BlockMaidBed();
    public static final ItemMaidBed maidBedItem = new ItemMaidBed();
    private static boolean registered;

    private MaidLifeContent() {}

    public static void register() {
        if (registered) throw new IllegalStateException(ContentRegistryDiagnostics.MSG_MAID_LIFE_CONTENT_IS_ALREADY);
        GameRegistry.registerBlock(snackCabinet, ItemSnackCabinet.class, ContentRegistryIds.ID_SNACK_CABINET);
        GameRegistry.registerBlock(maidBed, (Class<? extends ItemBlock>) null, ContentRegistryIds.ID_MAID_BED);
        GameRegistry.registerItem(maidBedItem, ContentRegistryIds.ID_MAID_BED);
        GameRegistry.registerTileEntity(TileEntitySnackCabinet.class, ContentRegistryIds.ID_TLMNH_SNACK_CABINET);
        GameRegistry.registerTileEntity(TileEntityMaidBed.class, ContentRegistryIds.ID_TLMNH_MAID_BED);
        registered = true;
    }
}
