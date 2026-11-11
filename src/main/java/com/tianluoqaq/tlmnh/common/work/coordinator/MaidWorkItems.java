package com.tianluoqaq.tlmnh.common.work.coordinator;

import java.util.function.Supplier;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.world.World;
import net.minecraftforge.oredict.RecipeSorter;

import com.tianluoqaq.tlmnh.common.work.bell.BellIds;
import com.tianluoqaq.tlmnh.common.work.bell.BellTuning;
import com.tianluoqaq.tlmnh.common.work.bell.BreedingBellData;
import com.tianluoqaq.tlmnh.common.work.bell.BreedingBellItem;
import com.tianluoqaq.tlmnh.common.work.bell.MaintenanceBellData;
import com.tianluoqaq.tlmnh.common.work.bell.MaintenanceBellItem;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider;

import cpw.mods.fml.common.registry.GameRegistry;

/** 维护铃铛与育种铃铛物品注册及无序单格清空绑定配方注册入口 */
public final class MaidWorkItems {

    public static MaintenanceBellItem maintenanceBell;
    public static BreedingBellItem breedingBell;

    private MaidWorkItems() {}

    public static void register(Supplier<CropsNhBreedingCompatProvider> cropsNhProvider) {
        if (maintenanceBell != null) return;
        maintenanceBell = new MaintenanceBellItem();
        breedingBell = new BreedingBellItem(cropsNhProvider);

        GameRegistry.registerItem(maintenanceBell, BellIds.REG_MAINTENANCE_BELL);
        GameRegistry.registerItem(breedingBell, BellIds.REG_BREEDING_BELL);

        RecipeSorter.register(
            BellIds.RECIPE_BELL_CLEAR,
            BellClearBindingRecipe.class,
            RecipeSorter.Category.SHAPELESS,
            BellIds.RECIPE_AFTER_SHAPELESS);
        // 单格无序合成仅清空绑定区块或田块子标签，完整保留自定义名称、附魔等其余 NBT
        GameRegistry.addRecipe(new BellClearBindingRecipe(maintenanceBell, true));
        GameRegistry.addRecipe(new BellClearBindingRecipe(breedingBell, false));
    }

    public static final class BellClearBindingRecipe implements IRecipe {

        private final Item targetBell;
        private final boolean maintenanceMode;

        public BellClearBindingRecipe(Item targetBell, boolean maintenanceMode) {
            this.targetBell = targetBell;
            this.maintenanceMode = maintenanceMode;
        }

        @Override
        public boolean matches(InventoryCrafting inv, World world) {
            return findSingleBoundBell(inv) != null;
        }

        @Override
        public ItemStack getCraftingResult(InventoryCrafting inv) {
            ItemStack found = findSingleBoundBell(inv);
            if (found == null) return null;
            ItemStack result = found.copy();
            result.stackSize = BellTuning.MAX_STACK_SIZE;
            if (maintenanceMode) {
                MaintenanceBellData.clear(result);
            } else {
                BreedingBellData.clear(result);
            }
            return result;
        }

        @Override
        public int getRecipeSize() {
            return BellTuning.ONE;
        }

        @Override
        public ItemStack getRecipeOutput() {
            return targetBell == null ? null : new ItemStack(targetBell);
        }

        private ItemStack findSingleBoundBell(InventoryCrafting inv) {
            if (inv == null || targetBell == null) return null;
            ItemStack single = null;
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                ItemStack slot = inv.getStackInSlot(i);
                if (slot == null) continue;
                if (single != null || slot.getItem() != targetBell) return null;
                boolean bound = maintenanceMode ? MaintenanceBellData.read(slot)
                    .isBound()
                    : BreedingBellData.read(slot)
                        .isBound();
                if (!bound) return null;
                single = slot;
            }
            return single;
        }
    }
}
