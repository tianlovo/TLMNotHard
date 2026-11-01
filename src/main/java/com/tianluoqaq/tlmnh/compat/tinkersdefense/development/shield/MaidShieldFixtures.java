package com.tianluoqaq.tlmnh.compat.tinkersdefense.development.shield;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;

/** 使用真实注册物品与ToolBuilder构造盾牌，不手填正常成品的InfiTool */
public final class MaidShieldFixtures {

    private MaidShieldFixtures() {}

    public static Map<String, ItemStack> createWoodShields(JsonObject construction) throws Exception {
        var result = new LinkedHashMap<String, ItemStack>();
        for (String material : new String[] { "wood", "hide", "iron", "diamond", "gold" }) {
            String id = "battlegear2:shield." + material;
            result.put(id, new ItemStack(registered(id)));
        }
        var tools = new com.tianluoqaq.tlmnh.compat.tconstruct.development.tool.TConstructToolBuilder(construction);
        var builds = new JsonArray();
        construction.add("builds", builds);
        for (boolean heater : new boolean[] { false, true }) {
            String id = heater ? "tinkersdefense:Heater Shield" : "tinkersdefense:Round Shield";
            var head = tools.part("largePlate");
            var handle = tools.part(heater ? "toughRod" : "toolRod");
            var accessory = tools.part(heater ? "largePlate" : "frypanHead");
            var extra = heater ? tools.part("toughBinding") : null;
            var row = new JsonObject();
            row.addProperty("registryId", id);
            row.addProperty("normalBuilderInvoked", true);
            var before = new JsonArray();
            for (ItemStack stack : new ItemStack[] { head, handle, accessory, extra }) before.add(snapshot(stack));
            row.add("partsBefore", before);
            builds.add(row);
            Object output = tools.build(head, handle, accessory, extra, heater);
            require(output instanceof ItemStack, "ToolBuilder rejected " + id);
            var stack = (ItemStack) output;
            row.add("output", snapshot(stack));
            require(stack.getItem() == registered(id) && stack.stackSize == 1, "ToolBuilder returned different shield");
            require(
                stack.hasTagCompound() && stack.getTagCompound()
                    .hasKey("InfiTool", 10),
                "Missing InfiTool");
            var tag = stack.getTagCompound()
                .getCompoundTag("InfiTool");
            for (String key : new String[] { "Head", "Handle", "Accessory" })
                require(tag.hasKey(key, 3) && tag.getInteger(key) == 0, "Builder changed " + key);
            require(!heater || tag.hasKey("Extra", 3) && tag.getInteger("Extra") == 0, "Builder changed extra");
            require(tag.hasKey("TotalDurability", 3) && tag.getInteger("TotalDurability") > 0, "Invalid durability");
            require(tag.hasKey("Damage", 3) && tag.getInteger("Damage") == 0, "Fresh tool already damaged");
            require(tag.hasKey("Unbreaking", 3) && tag.getInteger("Unbreaking") == 0, "Wood baseline reinforced");
            require(tag.hasKey("Broken", 1) && !tag.getBoolean("Broken"), "Fresh tool already broken");
            require(
                !stack.getTagCompound()
                    .hasKey("Energy")
                    && EnchantmentHelper.getEnchantmentLevel(Enchantment.unbreaking.effectId, stack) == 0,
                "Energy or vanilla Unbreaking changed deterministic baseline");
            result.put(id, stack);
        }
        construction.add(
            "battlegearIdentity",
            DevelopmentArtifactIdentity.capture(
                result.get("battlegear2:shield.wood")
                    .getItem()
                    .getClass(),
                com.tianluoqaq.tlmnh.compat.battlegear2.common.shield.BattlegearShieldAccess.apiType()));
        construction.add(
            "tinkersDefenseIdentity",
            DevelopmentArtifactIdentity.capture(
                result.get("tinkersdefense:Round Shield")
                    .getItem()
                    .getClass(),
                result.get("tinkersdefense:Heater Shield")
                    .getItem()
                    .getClass()));
        return result;
    }

    public static JsonObject snapshot(ItemStack stack) {
        var result = new JsonObject();
        result.addProperty("present", stack != null);
        if (stack == null) return result;
        result.addProperty("registryId", Item.itemRegistry.getNameForObject(stack.getItem()));
        result.addProperty(
            "itemClass",
            stack.getItem()
                .getClass()
                .getName());
        result.addProperty("count", stack.stackSize);
        // ToolCore.getDamage may synchronize the display damage; actual InfiTool Damage is recorded separately
        result.addProperty("displayDamage", stack.getItemDamage());
        result.addProperty("displayMaximum", stack.getMaxDamage());
        result.addProperty(
            "completeNbt",
            stack.writeToNBT(new NBTTagCompound())
                .toString());
        result.addProperty(
            "hasInfiTool",
            stack.hasTagCompound() && stack.getTagCompound()
                .hasKey("InfiTool", 10));
        if (result.get("hasInfiTool")
            .getAsBoolean()) {
            var raw = stack.getTagCompound()
                .getCompoundTag("InfiTool");
            var infi = new JsonObject();
            for (String key : new String[] { "Head", "Handle", "Accessory", "Extra", "Damage", "TotalDurability",
                "Unbreaking" }) {
                infi.addProperty(key + "PresentAsInt", raw.hasKey(key, 3));
                if (raw.hasKey(key, 3)) infi.addProperty(key, raw.getInteger(key));
            }
            infi.addProperty("Broken", raw.getBoolean("Broken"));
            result.add("infi", infi);
        }
        return result;
    }

    private static Item registered(String id) {
        require(Item.itemRegistry.containsKey(id), "Missing registry item: " + id);
        Object item = Item.itemRegistry.getObject(id);
        require(item instanceof Item, "Registry entry is not an Item: " + id);
        return (Item) item;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException("Shield fixture: " + message);
    }
}
