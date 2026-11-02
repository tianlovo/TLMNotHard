package com.tianluoqaq.tlmnh.common.progression;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import gregtech.api.enums.ItemList;
import gregtech.api.objects.OreDictItemStack;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.util.GTModHandler;
import gregtech.api.util.GTRecipeBuilder;

/** 按实际GT核心API注册，GTNH核心适配留在进度层，不混入可选内容模组目录 */
final class GregTechProgression {

    private static final long BITS = GTModHandler.RecipeBits.NOT_REMOVABLE
        | GTModHandler.RecipeBits.DO_NOT_CHECK_FOR_COLLISIONS;

    private GregTechProgression() {}

    static void register() {
        for (var ore : new String[] { "plateSteel", "plateBronze", "gemLapis", "plankWood", "blockWool", "circuitBasic",
            "bedWood" })
            if (OreDictionary.getOres(ore)
                .isEmpty()) throw new IllegalStateException("Missing progression input " + ore);
        var cake = ItemList.Food_Baked_Cake.get(1);
        var hull = ItemList.Hull_LV.get(1);
        if (cake == null || cake.getItem() == null || hull == null || hull.getItem() == null)
            throw new IllegalStateException("Missing GregTech baked cake or LV hull");
        var shrineInputs = new Object[] { new OreDictItemStack("plateSteel", 4), new OreDictItemStack("gemLapis", 1),
            new OreDictItemStack("circuitBasic", 2), hull, new OreDictItemStack("plankWood", 1) };
        // 回用框先用蒸汽期材料制作；每次充制核心仍消耗蛋糕和实际蒸汽加工
        add(
            new ItemStack(MaidResurrectionContent.EMPTY_SLAB),
            new Object[] { "LPL", "SBS", "RWR", 'L', "gemLapis", 'P', "plateSteel", 'S', "plateSteel", 'B',
                "plateBronze", 'R', "plateBronze", 'W', new ItemStack(Items.redstone) });
        if (GTRecipeBuilder.builder()
            .itemInputs(new ItemStack(MaidResurrectionContent.EMPTY_SLAB), cake)
            .itemOutputs(new ItemStack(MaidResurrectionContent.MAID_CORE))
            .duration(600)
            .eut(8)
            .addTo(RecipeMaps.alloySmelterRecipes)
            .isEmpty()) throw new IllegalStateException("GregTech rejected steam maid-core charging");
        // 真实LV装配机门槛，旧神龛工作台定义不再注册；旧核心/魂符不改写
        if (GTRecipeBuilder.builder()
            .itemInputs(shrineInputs)
            .itemOutputs(new ItemStack(MaidResurrectionContent.SHRINE))
            .duration(400)
            .eut(30)
            .addTo(RecipeMaps.assemblerRecipes)
            .isEmpty()) throw new IllegalStateException("GregTech rejected LV maid shrine assembly");
        add(
            new ItemStack(MaidLifeContent.maidBedItem),
            new Object[] { "WWW", "SBS", "PPP", 'W', "blockWool", 'S', "plateSteel", 'B', "bedWood", 'P',
                "plankWood" });
        add(
            new ItemStack(MaidLifeContent.snackCabinet),
            new Object[] { "PPP", "SCS", "PPP", 'P', "plankWood", 'S', "plateSteel", 'C',
                new ItemStack(Blocks.chest) });
    }

    private static void add(ItemStack output, Object[] input) {
        // GT接口会将原始Item/Block转为字符串；不能把它们误当作已解析的实际材料
        for (int i = 4; i < input.length; i += 2) {
            var ingredient = input[i];
            if (ingredient instanceof ItemStack stack) {
                if (stack.getItem() == null || stack.stackSize <= 0)
                    throw new IllegalArgumentException("Invalid progression item input");
            } else if (ingredient instanceof String ore) {
                if (OreDictionary.getOres(ore)
                    .isEmpty()) throw new IllegalArgumentException("Missing progression ore " + ore);
            } else throw new IllegalArgumentException("Progression inputs require item stacks or existing ore names");
        }
        if (!GTModHandler.addCraftingRecipe(output, BITS, input))
            throw new IllegalStateException("GregTech rejected maid progression recipe " + output.getUnlocalizedName());
    }
}
