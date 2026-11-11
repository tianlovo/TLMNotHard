package com.tianluoqaq.tlmnh.client.development;

import java.util.Arrays;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.compat.tinkersdefense.development.shield.MaidShieldFixtures;

/** 详情验收复用已登记物品和真实ToolBuilder，不生成手写匠魂成品或登记世界实体 */
public final class MaidDetailsItemFixtures {

    private MaidDetailsItemFixtures() {}

    public static ItemStack create(String action, JsonObject construction) throws Exception {
        if (!DevelopmentFlags.isDevCommandsEnabled()) throw new IllegalStateException("Development preview required");
        ItemStack stack;
        if (Arrays
            .asList(
                "shield_wood",
                "shield_hide",
                "shield_iron",
                "shield_diamond",
                "shield_gold",
                "shield_round",
                "shield_heater")
            .contains(action)) {
            String material = action.substring(7);
            String id = material.equals("round") ? "tinkersdefense:Round Shield"
                : material.equals("heater") ? "tinkersdefense:Heater Shield" : "battlegear2:shield." + material;
            stack = MaidShieldFixtures.createWoodShields(construction)
                .get(id)
                .copy();
        } else stack = switch (action) {
            case "main_icon" -> new ItemStack(Items.paper);
            case "main_bow" -> new ItemStack(Items.bow);
            case "main_block" -> new ItemStack(Blocks.stone);
            case "main_tool" -> new ItemStack(Items.iron_sword);
            default -> throw new IllegalArgumentException("Unknown native preview item");
        };
        var raw = ProtectedItemCodec.encode(stack);
        var admitted = ProtectedItemCodec.decode(raw);
        if (!ItemStack.areItemStacksEqual(stack, admitted)) throw new IllegalStateException(
            "Native preview item did not survive its complete protected NBT round trip");
        construction.addProperty("action", action);
        construction.add("item", MaidShieldFixtures.snapshot(admitted));
        return admitted;
    }
}
