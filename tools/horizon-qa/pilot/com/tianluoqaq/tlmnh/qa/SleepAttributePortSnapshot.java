package com.tianluoqaq.tlmnh.qa;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttribute;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** 睡眠输入的独立属性读取；保留GTNH附加属性和完整原始NBT，不注册或改写属性 */
final class SleepAttributePortSnapshot {

    private static final String[] KEYS = { "max_health", "movement_speed", "follow_range", "knockback_resistance",
        "attack_damage" };
    private static final IAttribute[] ATTRIBUTES = { SharedMonsterAttributes.maxHealth,
        SharedMonsterAttributes.movementSpeed, SharedMonsterAttributes.followRange,
        SharedMonsterAttributes.knockbackResistance, SharedMonsterAttributes.attackDamage };

    private SleepAttributePortSnapshot() {}

    static JsonObject capture(EntityMaid maid) {
        var map = maid.getAttributeMap();
        var membership = new HashSet<>(map.getAllAttributes());
        var saved = new NBTTagCompound();
        maid.writeToNBT(saved);
        var before = (NBTTagCompound) saved.copy();
        var result = new JsonObject();
        result.addProperty("schema", "sleep-attribute-inputs-v1");
        result.addProperty("side", "port");
        result.addProperty("samplePhase", "registered_spawn_before_first_natural_tick");
        result.addProperty("rawEntityNBT", before.toString());
        result.add("rawEntityNbtBinary", binaryNbt(before));
        result.addProperty(
            "rawAttributesNBT",
            before.getTag("Attributes")
                .toString());
        result.addProperty(
            "serializedAttributeCount",
            before.getTagList("Attributes", 10)
                .tagCount());
        var selectedNames = new HashSet<String>();
        var rows = new JsonArray();
        for (int index = 0; index < KEYS.length; index++) {
            var attribute = ATTRIBUTES[index];
            selectedNames.add(attribute.getAttributeUnlocalizedName());
            var instance = map.getAttributeInstance(attribute);
            var row = new JsonObject();
            row.addProperty("key", KEYS[index]);
            row.addProperty("platformName", attribute.getAttributeUnlocalizedName());
            row.addProperty("present", instance != null);
            row.addProperty("storage", instance == null ? "absent" : "instance");
            if (instance != null) {
                row.addProperty("base", finite(instance.getBaseValue()));
                row.addProperty("value", finite(instance.getAttributeValue()));
                row.add("modifiers", modifiers(instance));
            }
            rows.add(row);
        }
        var available = new TreeSet<String>();
        for (Object entry : map.getAllAttributes()) {
            if (!(entry instanceof IAttributeInstance instance))
                throw new IllegalStateException("Native attribute instance map shape differs");
            available.add(
                instance.getAttribute()
                    .getAttributeUnlocalizedName());
        }
        result.add("availableAttributeNames", strings(available));
        result.add("materializedAttributeNames", strings(available));
        available.removeAll(selectedNames);
        result.add("unmappedAttributeNames", strings(available));
        result.add("attributes", rows);
        var after = new NBTTagCompound();
        maid.writeToNBT(after);
        if (!before.equals(after) || !membership.equals(new HashSet<>(map.getAllAttributes())))
            throw new IllegalStateException("Attribute observation changed raw NBT or attribute membership");
        result.addProperty("rawNbtUnchanged", true);
        result.addProperty("attributeInstanceMembershipUnchanged", true);
        return result;
    }

    /** 原生未压缩NBT保留全部tag类型/数组/列表顺序；旧toString本身不是无损格式 */
    static JsonObject binaryNbt(NBTTagCompound raw) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                CompressedStreamTools.write(raw, output);
            }
            var result = new JsonObject();
            result.addProperty("schema", "named-uncompressed-nbt-v1");
            result.addProperty("encoding", "base64");
            result.addProperty("rawSNBT", raw.toString());
            result.addProperty(
                "data",
                Base64.getEncoder()
                    .encodeToString(bytes.toByteArray()));
            return result;
        } catch (IOException failure) {
            throw new IllegalStateException("Complete initial native NBT binary capture failed", failure);
        }
    }

    private static JsonArray modifiers(IAttributeInstance instance) {
        var modifiers = new ArrayList<AttributeModifier>(instance.func_111122_c());
        modifiers.sort(
            Comparator.comparing(
                modifier -> modifier.getID()
                    .toString()));
        var result = new JsonArray();
        for (var modifier : modifiers) {
            var row = new JsonObject();
            row.addProperty(
                "uuid",
                modifier.getID()
                    .toString());
            row.addProperty("name", modifier.getName());
            row.addProperty("operation", modifier.getOperation());
            row.addProperty("amount", finite(modifier.getAmount()));
            row.addProperty("persistent", modifier.isSaved());
            result.add(row);
        }
        return result;
    }

    private static JsonArray strings(Set<String> names) {
        var result = new JsonArray();
        for (String name : names) result.add(new JsonPrimitive(name));
        return result;
    }

    private static double finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalStateException("Nonfinite native attribute input");
        return value;
    }
}
