package com.tianluoqaq.tlmnh.common.work.bell;

import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.api.bauble.IMaidBauble;
import com.tianluoqaq.tlmnh.common.work.bell.MaintenanceBellData.ToggleResult;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 维护铃铛物品：
 * 潜行右键地面绑定或解绑当前区块（上限 16 个非连续区块、包围盒跨度 <= 8），可穿戴于女仆饰品栏
 */
public final class MaintenanceBellItem extends Item implements IMaidBauble {

    public MaintenanceBellItem() {
        setMaxStackSize(BellTuning.MAX_STACK_SIZE);
        setUnlocalizedName(BellIds.UNLOC_MAINTENANCE_BELL);
        setTextureName(BellIds.TEX_MAINTENANCE_BELL);
        setCreativeTab(CreativeTabs.tabTools);
    }

    @Override
    public boolean onItemUseFirst(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        if (stack == null || player == null || world == null || !player.isSneaking()) {
            return false;
        }
        if (world.isRemote) {
            return false;
        }
        int chunkX = x >> BellTuning.BLOCK_TO_CHUNK_SHIFT;
        int chunkZ = z >> BellTuning.BLOCK_TO_CHUNK_SHIFT;
        int dim = world.provider.dimensionId;
        ToggleResult res = MaintenanceBellData.toggleChunk(stack, dim, chunkX, chunkZ);
        switch (res.outcome) {
            case BOUND -> player.addChatMessage(
                new ChatComponentTranslation(
                    BellIds.MSG_MAINT_BOUND,
                    chunkX,
                    chunkZ,
                    res.boundCount,
                    BellTuning.MAX_BOUND_CHUNKS));
            case UNBOUND -> player.addChatMessage(
                new ChatComponentTranslation(
                    BellIds.MSG_MAINT_UNBOUND,
                    chunkX,
                    chunkZ,
                    res.boundCount,
                    BellTuning.MAX_BOUND_CHUNKS));
            case CLEARED_ALL -> player.addChatMessage(new ChatComponentTranslation(BellIds.MSG_MAINT_CLEARED));
            case REJECTED_CHUNK_LIMIT -> player
                .addChatMessage(new ChatComponentTranslation(BellIds.MSG_MAINT_LIMIT, BellTuning.MAX_BOUND_CHUNKS));
            case REJECTED_SPAN_EXCEEDED -> player.addChatMessage(
                new ChatComponentTranslation(BellIds.MSG_MAINT_SPAN, BellTuning.MAX_CHUNK_SPAN, res.spanX, res.spanZ));
            case REJECTED_WRONG_DIMENSION -> player.addChatMessage(
                new ChatComponentTranslation(
                    BellIds.MSG_MAINT_WRONG_DIM,
                    MaintenanceBellData.read(stack)
                        .dimension()));
        }
        player.inventoryContainer.detectAndSendChanges();
        return true;
    }

    @Override
    public boolean onItemUse(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        return player != null && player.isSneaking();
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void addInformation(ItemStack stack, EntityPlayer player, List tooltip, boolean advanced) {
        MaintenanceBellData data = MaintenanceBellData.read(stack);
        if (!data.isBound()) {
            tooltip.add(StatCollector.translateToLocal(BellIds.TIP_UNBOUND));
            return;
        }
        tooltip.add(
            StatCollector.translateToLocalFormatted(
                BellIds.TIP_MAINT_SUMMARY,
                data.dimension(),
                data.chunkCount(),
                BellTuning.MAX_BOUND_CHUNKS));
        List<int[]> chunks = data.chunks();
        for (int i = 0; i < chunks.size(); i++) {
            int[] pair = chunks.get(i);
            tooltip.add(
                StatCollector.translateToLocalFormatted(
                    BellIds.TIP_MAINT_CHUNK_LINE,
                    pair[BellTuning.IDX_X],
                    pair[BellTuning.IDX_Z]));
        }
        tooltip.add(StatCollector.translateToLocal(BellIds.TIP_CLEAR_RECIPE));
    }
}
