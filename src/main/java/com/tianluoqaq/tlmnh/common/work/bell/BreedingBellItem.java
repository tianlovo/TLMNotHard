package com.tianluoqaq.tlmnh.common.work.bell;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.api.bauble.IMaidBauble;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider.PlotValidationResult;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 育种铃铛物品：
 * 潜行右键绑定或解绑 3x3 CropsNH 可接受土壤田块区域，可穿戴于女仆饰品栏
 */
public final class BreedingBellItem extends Item implements IMaidBauble {

    private final Supplier<CropsNhBreedingCompatProvider> compatSupplier;

    public BreedingBellItem(Supplier<CropsNhBreedingCompatProvider> compatSupplier) {
        this.compatSupplier = compatSupplier;
        setMaxStackSize(BellTuning.MAX_STACK_SIZE);
        setUnlocalizedName(BellIds.UNLOC_BREEDING_BELL);
        setTextureName(BellIds.TEX_BREEDING_BELL);
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
        CropsNhBreedingCompatProvider compat = compatSupplier != null ? compatSupplier.get()
            : CropsNhBreedingCompatProvider.NOOP;
        int soilY = compat.isAvailable() && compat.isCropSticksBlock(world, x, y, z) ? y - BellTuning.ONE : y;
        int dim = world.provider.dimensionId;
        BreedingBellData existing = BreedingBellData.read(stack);
        if (existing.matches(dim, x, soilY, z)) {
            BreedingBellData.clear(stack);
            player.addChatMessage(new ChatComponentTranslation(BellIds.MSG_BREED_UNBOUND, x, soilY, z));
            player.inventoryContainer.detectAndSendChanges();
            return true;
        }
        if (!compat.isAvailable()) {
            player.addChatMessage(new ChatComponentTranslation(BellIds.MSG_BREED_NO_MOD));
            return true;
        }
        PlotValidationResult check = compat.validateBreedingPlot(world, x, soilY, z);
        if (!check.valid) {
            switch (check.issue) {
                case UNLOADED_CHUNK -> player.addChatMessage(new ChatComponentTranslation(BellIds.MSG_BREED_UNLOADED));
                case ABOVE_BLOCKED -> player.addChatMessage(
                    new ChatComponentTranslation(BellIds.MSG_BREED_ABOVE_BLOCKED, check.badX, check.badY, check.badZ));
                default -> player.addChatMessage(
                    new ChatComponentTranslation(BellIds.MSG_BREED_INVALID_SOIL, check.badX, check.badY, check.badZ));
            }
            return true;
        }
        BreedingBellData.bind(stack, dim, x, soilY, z);
        player.addChatMessage(
            new ChatComponentTranslation(
                BellIds.MSG_BREED_BOUND,
                x - BellTuning.PLOT_RADIUS,
                soilY,
                z - BellTuning.PLOT_RADIUS,
                x + BellTuning.PLOT_RADIUS,
                z + BellTuning.PLOT_RADIUS));
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
        BreedingBellData data = BreedingBellData.read(stack);
        if (!data.isBound()) {
            tooltip.add(StatCollector.translateToLocal(BellIds.TIP_UNBOUND));
            return;
        }
        tooltip.add(
            StatCollector.translateToLocalFormatted(
                BellIds.TIP_BREED_SUMMARY,
                data.dimension(),
                data.centerX(),
                data.soilY(),
                data.centerZ()));
        tooltip.add(StatCollector.translateToLocal(BellIds.TIP_CLEAR_RECIPE));
    }
}
