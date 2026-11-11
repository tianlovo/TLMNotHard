package com.tianluoqaq.tlmnh.compat;

import java.util.function.Consumer;

import net.minecraft.item.ItemStack;

import com.tianluoqaq.tlmnh.common.inventory.spi.BackpackContainerCompatProvider;
import com.tianluoqaq.tlmnh.compat.adventurebackpack.common.inventory.AdventureBackpackContainer;
import com.tianluoqaq.tlmnh.compat.backpack.common.inventory.EydamosBackpackContainer;
import com.tianluoqaq.tlmnh.compat.extrautilities.common.inventory.GoldenBagContainer;
import com.tianluoqaq.tlmnh.compat.forestry.common.inventory.ForestryBackpackContainer;
import com.tianluoqaq.tlmnh.compat.projredexploration.common.inventory.ProjectRedBackpackContainer;

/** 五大背包容器统一分发器，挂载至 CompatServices.backpacks() */
public final class BackpackCompatDispatcher implements BackpackContainerCompatProvider {

    @Override
    public boolean isSupportedBackpack(ItemStack containerStack) {
        if (containerStack == null || containerStack.getItem() == null) return false;
        return EydamosBackpackContainer.isSupported(containerStack)
            || AdventureBackpackContainer.isSupported(containerStack)
            || ForestryBackpackContainer.isSupported(containerStack)
            || GoldenBagContainer.isSupported(containerStack)
            || ProjectRedBackpackContainer.isSupported(containerStack);
    }

    @Override
    public ItemStack insertIntoBackpack(ItemStack containerStack, ItemStack input, boolean simulate) {
        if (input == null || input.stackSize <= 0) return null;
        if (containerStack == null || containerStack.getItem() == null) return input.copy();
        if (EydamosBackpackContainer.isSupported(containerStack)) {
            return EydamosBackpackContainer.insert(containerStack, input, simulate);
        }
        if (AdventureBackpackContainer.isSupported(containerStack)) {
            return AdventureBackpackContainer.insert(containerStack, input, simulate);
        }
        if (ForestryBackpackContainer.isSupported(containerStack)) {
            return ForestryBackpackContainer.insert(containerStack, input, simulate);
        }
        if (GoldenBagContainer.isSupported(containerStack)) {
            return GoldenBagContainer.insert(containerStack, input, simulate);
        }
        if (ProjectRedBackpackContainer.isSupported(containerStack)) {
            return ProjectRedBackpackContainer.insert(containerStack, input, simulate);
        }
        return input.copy();
    }

    @Override
    public boolean forEachStoredStack(ItemStack containerStack, Consumer<ItemStack> visitor) {
        if (containerStack == null || containerStack.getItem() == null) return false;
        if (EydamosBackpackContainer.isSupported(containerStack)) {
            return EydamosBackpackContainer.forEachStored(containerStack, visitor);
        }
        if (AdventureBackpackContainer.isSupported(containerStack)) {
            return AdventureBackpackContainer.forEachStored(containerStack, visitor);
        }
        if (ForestryBackpackContainer.isSupported(containerStack)) {
            return ForestryBackpackContainer.forEachStored(containerStack, visitor);
        }
        if (GoldenBagContainer.isSupported(containerStack)) {
            return GoldenBagContainer.forEachStored(containerStack, visitor);
        }
        if (ProjectRedBackpackContainer.isSupported(containerStack)) {
            return ProjectRedBackpackContainer.forEachStored(containerStack, visitor);
        }
        return false;
    }
}
