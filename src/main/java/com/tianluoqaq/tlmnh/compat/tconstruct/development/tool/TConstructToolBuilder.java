package com.tianluoqaq.tlmnh.compat.tconstruct.development.tool;

import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;

/** 使用真实材料表与ToolBuilder，供开发夹具创建合法工具 */
public final class TConstructToolBuilder {

    private final Class<?> builderType, parts;
    private final Object builder;

    public TConstructToolBuilder(JsonObject construction) throws Exception {
        var registry = Class.forName("tconstruct.library.TConstructRegistry");
        Object materials = registry.getField("toolMaterials")
            .get(null);
        require(materials instanceof Map, "Actual material registry is not a map");
        Object wood = ((Map<?, ?>) materials).get(0);
        require(wood != null, "Actual material zero is missing");
        String materialName = (String) wood.getClass()
            .getField("materialName")
            .get(wood);
        int reinforced = wood.getClass()
            .getField("reinforced")
            .getInt(wood);
        require("Wood".equals(materialName) && reinforced == 0, "Wood baseline changed in actual native");
        var material = new JsonObject();
        material.addProperty("id", 0);
        material.addProperty("name", materialName);
        material.addProperty("reinforced", reinforced);
        material.addProperty(
            "durability",
            wood.getClass()
                .getField("durability")
                .getInt(wood));
        construction.add("material", material);
        builderType = Class.forName("tconstruct.library.crafting.ToolBuilder");
        builder = builderType.getField("instance")
            .get(null);
        require(builder != null, "Actual ToolBuilder is missing");
        parts = Class.forName("tconstruct.tools.TinkerTools");
        construction.add(
            "tconstructIdentity",
            DevelopmentArtifactIdentity.capture(registry, builderType, parts, wood.getClass()));
    }

    public ItemStack part(String field) throws Exception {
        Object item = parts.getField(field)
            .get(null);
        require(item instanceof Item, "Registered tool part missing: " + field);
        return new ItemStack((Item) item, 1, 0);
    }

    public Object build(ItemStack head, ItemStack handle, ItemStack accessory, ItemStack extra, boolean heater)
        throws Exception {
        if (heater) return builderType
            .getMethod("buildTool", ItemStack.class, ItemStack.class, ItemStack.class, ItemStack.class, String.class)
            .invoke(builder, head, handle, accessory, extra, "");
        return builderType.getMethod("buildTool", ItemStack.class, ItemStack.class, ItemStack.class, String.class)
            .invoke(builder, head, handle, accessory, "");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException("Shield fixture: " + message);
    }
}
