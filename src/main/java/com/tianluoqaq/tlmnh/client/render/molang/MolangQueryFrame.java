package com.tianluoqaq.tlmnh.client.render.molang;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 单次绘制所需的查询数据，不包含世界、实体或物品实例引用 */
public final class MolangQueryFrame {

    // RC2 注册表包含 IC2:itemArmor... 这类旧 ID，大小写必须原样保留
    private static final Pattern ITEM_ID = Pattern.compile("[A-Za-z0-9_.-]+:[A-Za-z0-9/._-]+");
    private final Map<Slot, Equipment> equipment = new EnumMap<>(Slot.class);
    public double lifetime, bodyYaw, netHeadYaw, headPitch, health, maxHealth, groundSpeed, verticalSpeed, yawSpeed;
    public boolean inWater, inWaterOrRain, jumping, sneaking;
    public double previousX, previousY, previousZ, x, y, z, partialTick;

    public double position(int axis) {
        switch (axis) {
            case 0:
                return previousX + (x - previousX) * partialTick;
            case 1:
                return previousY + (y - previousY) * partialTick;
            case 2:
                return previousZ + (z - previousZ) * partialTick;
            default:
                throw new IllegalArgumentException("Invalid position axis");
        }
    }

    public double positionDelta(int axis) {
        switch (axis) {
            case 0:
                return (x - previousX) * partialTick;
            case 1:
                return (y - previousY) * partialTick;
            case 2:
                return (z - previousZ) * partialTick;
            default:
                throw new IllegalArgumentException("Invalid position axis");
        }
    }

    public void setEquipment(Slot slot, String itemId, int maxDamage, int damage) {
        if (itemId == null) {
            equipment.remove(slot);
            return;
        }
        // 实际注册表数据不经高版本 ID 校验，避免旧模组的合法物品中断整帧动画
        String qualified = itemId.indexOf(':') < 0 ? "minecraft:" + itemId : itemId;
        equipment.put(slot, new Equipment(qualified, maxDamage, damage));
    }

    public Equipment equipment(Slot slot) {
        return equipment.get(slot);
    }

    public static String canonicalItemId(String value) {
        if (value == null) return null;
        String result = value.indexOf(':') < 0 ? "minecraft:" + value : value;
        return ITEM_ID.matcher(result)
            .matches() ? result : null;
    }

    public enum Slot {

        MAINHAND,
        OFFHAND,
        HEAD,
        CHEST,
        LEGS,
        FEET;

        public static Slot fromName(String name) {
            if (name == null) return null;
            try {
                return valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                return null;
            }
        }
    }

    public static final class Equipment {

        public final String itemId;
        public final int maxDamage, damage;

        private Equipment(String itemId, int maxDamage, int damage) {
            this.itemId = itemId;
            this.maxDamage = maxDamage;
            this.damage = damage;
        }
    }
}
