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

    private GregTechProgression() {}

    static void register() {
        for (var ore : new String[] { ProgressionIds.ID_PLATESTEEL, ProgressionIds.ID_PLATEBRONZE,
            ProgressionIds.ID_GEMLAPIS, ProgressionIds.ID_PLANKWOOD, ProgressionIds.ID_BLOCKWOOL,
            ProgressionIds.ID_CIRCUITBASIC, ProgressionIds.ID_BEDWOOD })
            if (OreDictionary.getOres(ore)
                .isEmpty()) throw new IllegalStateException(ProgressionDiagnostics.MSG_MISSING_PROGRESSION_INPUT + ore);
        var cake = ItemList.Food_Baked_Cake.get(ProgressionTuning.GET_SINGLE_UNIT);
        var hull = ItemList.Hull_LV.get(ProgressionTuning.GET_SINGLE_UNIT);
        if (cake == null || cake.getItem() == null || hull == null || hull.getItem() == null)
            throw new IllegalStateException(ProgressionDiagnostics.MSG_MISSING_GREGTECH_BAKED_CAKE_OR);
        var shrineInputs = new Object[] {
            new OreDictItemStack(
                ProgressionIds.ID_PLATESTEEL,
                ProgressionTuning.GREG_TECH_ORE_DICT_ITEM_STACK_SECOND_PARAM),
            new OreDictItemStack(ProgressionIds.ID_GEMLAPIS, ProgressionTuning.GET_SINGLE_UNIT),
            new OreDictItemStack(
                ProgressionIds.ID_CIRCUITBASIC,
                ProgressionTuning.GREG_TECH_ORE_DICT_ITEM_STACK_SECOND_PARAM_2),
            hull, new OreDictItemStack(ProgressionIds.ID_PLANKWOOD, ProgressionTuning.GET_SINGLE_UNIT) };
        // 回用框先用蒸汽期材料制作；每次充制核心仍消耗蛋糕和实际蒸汽加工
        add(
            new ItemStack(MaidResurrectionContent.EMPTY_SLAB),
            new Object[] { ProgressionIds.KEY_LPL, ProgressionIds.KEY_SBS, ProgressionIds.KEY_RWR, 'L',
                ProgressionIds.ID_GEMLAPIS, 'P', ProgressionIds.ID_PLATESTEEL, 'S', ProgressionIds.ID_PLATESTEEL, 'B',
                ProgressionIds.ID_PLATEBRONZE, 'R', ProgressionIds.ID_PLATEBRONZE, 'W',
                new ItemStack(Items.redstone) });
        if (GTRecipeBuilder.builder()
            .itemInputs(new ItemStack(MaidResurrectionContent.EMPTY_SLAB), cake)
            .itemOutputs(new ItemStack(MaidResurrectionContent.MAID_CORE))
            .duration(ProgressionTuning.GREG_TECH_DURATION_FIRST_PARAM)
            .eut(ProgressionTuning.GREG_TECH_EUT_FIRST_PARAM)
            .addTo(RecipeMaps.alloySmelterRecipes)
            .isEmpty()) throw new IllegalStateException(ProgressionDiagnostics.MSG_GREGTECH_REJECTED_STEAM_MAID_CORE);
        // 真实LV装配机门槛，旧神龛工作台定义不再注册；旧核心/魂符不改写
        if (GTRecipeBuilder.builder()
            .itemInputs(shrineInputs)
            .itemOutputs(new ItemStack(MaidResurrectionContent.SHRINE))
            .duration(ProgressionTuning.GREG_TECH_DURATION_FIRST_PARAM_2)
            .eut(ProgressionTuning.GREG_TECH_EUT_FIRST_PARAM_2)
            .addTo(RecipeMaps.assemblerRecipes)
            .isEmpty()) throw new IllegalStateException(ProgressionDiagnostics.MSG_GREGTECH_REJECTED_LV_MAID_SHRINE);
        add(
            new ItemStack(MaidLifeContent.maidBedItem),
            new Object[] { ProgressionIds.KEY_WWW, ProgressionIds.KEY_SBS, ProgressionIds.KEY_PPP, 'W',
                ProgressionIds.ID_BLOCKWOOL, 'S', ProgressionIds.ID_PLATESTEEL, 'B', ProgressionIds.ID_BEDWOOD, 'P',
                ProgressionIds.ID_PLANKWOOD });
        add(
            new ItemStack(MaidLifeContent.snackCabinet),
            new Object[] { ProgressionIds.KEY_PPP, ProgressionIds.KEY_SCS, ProgressionIds.KEY_PPP, 'P',
                ProgressionIds.ID_PLANKWOOD, 'S', ProgressionIds.ID_PLATESTEEL, 'C', new ItemStack(Blocks.chest) });
    }

    private static void add(ItemStack output, Object[] input) {
        // GT接口会将原始Item/Block转为字符串；不能把它们误当作已解析的实际材料
        for (int i = ProgressionTuning.GREG_TECH_ORE_DICT_ITEM_STACK_SECOND_PARAM; i
            < input.length; i += ProgressionTuning.GREG_TECH_ORE_DICT_ITEM_STACK_SECOND_PARAM_2) {
            var ingredient = input[i];
            if (ingredient instanceof ItemStack stack) {
                if (stack.getItem() == null || stack.stackSize <= 0)
                    throw new IllegalArgumentException(ProgressionDiagnostics.MSG_INVALID_PROGRESSION_ITEM_INPUT);
            } else if (ingredient instanceof String ore) {
                if (OreDictionary.getOres(ore)
                    .isEmpty())
                    throw new IllegalArgumentException(ProgressionDiagnostics.MSG_MISSING_PROGRESSION_ORE + ore);
            } else
                throw new IllegalArgumentException(ProgressionDiagnostics.MSG_PROGRESSION_INPUTS_REQUIRE_ITEM_STACKS);
        }
        if (!GTModHandler.addCraftingRecipe(output, GregTechProgressionTuning.BITS, input))
            throw new IllegalStateException(
                ProgressionDiagnostics.MSG_GREGTECH_REJECTED_MAID_PROGRESSION_RECIPE + output.getUnlocalizedName());
    }
}
