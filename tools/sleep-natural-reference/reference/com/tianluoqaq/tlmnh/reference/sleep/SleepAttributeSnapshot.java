package com.tianluoqaq.tlmnh.reference.sleep;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** 读取真实实例或真实默认供应器，不用getInstance实化lazy属性，不设置属性或随机源 */
final class SleepAttributeSnapshot {

    private static final String[] KEYS = { "max_health", "movement_speed", "follow_range",
        "knockback_resistance", "attack_damage" };
    private static final Attribute[] ATTRIBUTES = { Attributes.MAX_HEALTH, Attributes.MOVEMENT_SPEED,
        Attributes.FOLLOW_RANGE, Attributes.KNOCKBACK_RESISTANCE, Attributes.ATTACK_DAMAGE };

    private SleepAttributeSnapshot() {}

    static JsonObject capture(EntityMaid maid) {
        try {
            var map = maid.getAttributes();
            var instancesField = field(AttributeMap.class, "attributes");
            var supplierField = field(AttributeMap.class, "supplier");
            var defaultsField = field(AttributeSupplier.class, "instances");
            var instances = instances(instancesField.get(map));
            var supplier = (AttributeSupplier) supplierField.get(map);
            var defaults = instances(defaultsField.get(supplier));
            var beforeInstances = new HashMap<>(instances);
            var beforeDefaults = new HashMap<>(defaults);
            var saved = new CompoundTag();
            maid.saveWithoutId(saved);
            var before = saved.copy();
            var result = new JsonObject();
            result.addProperty("schema", "sleep-attribute-inputs-v1");
            result.addProperty("side", "original");
            result.addProperty("samplePhase", "registered_spawn_before_first_natural_tick");
            result.addProperty("rawEntityNBT", before.toString());
            result.addProperty("rawAttributesNBT", before.get("Attributes").toString());
            result.addProperty("serializedAttributeCount", before.getList("Attributes", 10).size());
            var selectedNames = new HashSet<String>();
            var rows = new JsonArray();
            for (int index = 0; index < KEYS.length; index++) {
                var attribute = ATTRIBUTES[index];
                String name = name(attribute);
                selectedNames.add(name);
                var instance = instances.get(attribute);
                String storage = "instance";
                if (instance == null) {
                    instance = defaults.get(attribute);
                    storage = "supplier_default";
                }
                var row = new JsonObject();
                row.addProperty("key", KEYS[index]);
                row.addProperty("platformName", name);
                row.addProperty("present", instance != null);
                row.addProperty("storage", instance == null ? "absent" : storage);
                if (instance != null) {
                    row.addProperty("base", finite(instance.getBaseValue()));
                    row.addProperty("value", finite(instance.getValue()));
                    row.add("modifiers", modifiers(instance));
                }
                rows.add(row);
            }
            var available = new TreeSet<String>();
            for (var attribute : defaults.keySet()) available.add(name(attribute));
            for (var attribute : instances.keySet()) available.add(name(attribute));
            result.add("availableAttributeNames", strings(available));
            var materialized = new TreeSet<String>();
            for (var attribute : instances.keySet()) materialized.add(name(attribute));
            result.add("materializedAttributeNames", strings(materialized));
            available.removeAll(selectedNames);
            result.add("unmappedAttributeNames", strings(available));
            result.add("attributes", rows);
            var after = new CompoundTag();
            maid.saveWithoutId(after);
            if (!before.equals(after) || !beforeInstances.equals(instances) || !beforeDefaults.equals(defaults))
                throw new IllegalStateException("Attribute observation changed raw NBT or lazy instance membership");
            result.addProperty("rawNbtUnchanged", true);
            result.addProperty("attributeInstanceMembershipUnchanged", true);
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Reviewed original attribute layout is unavailable", failure);
        }
    }

    private static JsonArray modifiers(AttributeInstance instance) {
        Set<UUID> saved = new HashSet<>();
        var serialized = instance.save().getList("Modifiers", 10);
        for (int index = 0; index < serialized.size(); index++) saved.add(serialized.getCompound(index).getUUID("UUID"));
        var modifiers = new ArrayList<>(instance.getModifiers());
        modifiers.sort(Comparator.comparing(modifier -> modifier.getId().toString()));
        var result = new JsonArray();
        for (AttributeModifier modifier : modifiers) {
            var row = new JsonObject();
            row.addProperty("uuid", modifier.getId().toString());
            row.addProperty("name", modifier.getName());
            row.addProperty("operation", modifier.getOperation().toValue());
            row.addProperty("amount", finite(modifier.getAmount()));
            row.addProperty("persistent", saved.contains(modifier.getId()));
            result.add(row);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<Attribute, AttributeInstance> instances(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalStateException("Attribute instance map missing");
        for (var entry : map.entrySet())
            if (!(entry.getKey() instanceof Attribute) || !(entry.getValue() instanceof AttributeInstance))
                throw new IllegalStateException("Attribute instance map shape differs");
        return (Map<Attribute, AttributeInstance>) map;
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static String name(Attribute attribute) {
        var name = BuiltInRegistries.ATTRIBUTE.getKey(attribute);
        if (name == null) throw new IllegalStateException("Unregistered original attribute");
        return name.toString();
    }

    private static JsonArray strings(Set<String> names) {
        var result = new JsonArray();
        for (String name : names) result.add(name);
        return result;
    }

    private static double finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalStateException("Nonfinite original attribute input");
        return value;
    }
}
