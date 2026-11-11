package com.tianluoqaq.tlmnh.common.registry;

import net.minecraft.item.ItemBlock;

import com.tianluoqaq.tlmnh.common.life.BlockMaidBed;
import com.tianluoqaq.tlmnh.common.life.BlockSnackCabinet;
import com.tianluoqaq.tlmnh.common.life.ItemMaidBed;
import com.tianluoqaq.tlmnh.common.life.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.life.TileEntitySnackCabinet;
import com.tianluoqaq.tlmnh.common.life.cabinet.ItemSnackCabinet;

import cpw.mods.fml.common.registry.GameRegistry;

/** 首版生活核心注册；蒸汽材料配方由进度层统一注册 */
public final class MaidLifeContent {

    public static final BlockSnackCabinet snackCabinet = new BlockSnackCabinet();
    public static final BlockMaidBed maidBed = new BlockMaidBed();
    public static final ItemMaidBed maidBedItem = new ItemMaidBed();
    private static boolean registered;

    private MaidLifeContent() {}

    public static void register() {
        if (registered) throw new IllegalStateException("Maid life content is already registered");
        GameRegistry.registerBlock(snackCabinet, ItemSnackCabinet.class, "snack_cabinet");
        GameRegistry.registerBlock(maidBed, (Class<? extends ItemBlock>) null, "maid_bed");
        GameRegistry.registerItem(maidBedItem, "maid_bed");
        GameRegistry.registerTileEntity(TileEntitySnackCabinet.class, "tlmnh:snack_cabinet");
        GameRegistry.registerTileEntity(TileEntityMaidBed.class, "tlmnh:maid_bed");
        registered = true;
    }
}
