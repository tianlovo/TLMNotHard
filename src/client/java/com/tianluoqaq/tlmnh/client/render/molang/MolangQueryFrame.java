package com.tianluoqaq.tlmnh.client.render.molang;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** 单次绘制所需的查询数据，不包含世界、实体或物品实例引用 */
public final class MolangQueryFrame {

    // native 注册表包含 IC2:itemArmor... 这类旧 ID，大小写必须原样保留
    private static final Pattern ITEM_ID = Pattern.compile(MolangRenderIds.ID_A_ZA_Z0_9_A_ZA_Z0_9);
    private final Map<Slot, Equipment> equipment = new EnumMap<>(Slot.class);
    public double lifetime, bodyYaw, netHeadYaw, headPitch, health, maxHealth, groundSpeed, verticalSpeed, yawSpeed;
    public boolean inWater, inWaterOrRain, jumping, sneaking;
    public boolean begging, sitting, hasBackpack, closeEyes, hasElytra;
    public double foodLevel = MolangRenderLayout.MOLANG_FRAMES_GROUND_SPEED;
    public float viewYaw;
    public double previousX, previousY, previousZ, x, y, z, partialTick;

    public double position(int axis) {
        switch (axis) {
            case MolangRenderLayout.MAX_INITIAL_VALUE:
                return previousX + (x - previousX) * partialTick;
            case MolangRenderLayout.MIN_SINGLE_UNIT:
                return previousY + (y - previousY) * partialTick;
            case MolangRenderLayout.MOLANG_FRAMES_GET_EQUIPMENT_IN_SLOT_FIRST_PARAM_3:
                return previousZ + (z - previousZ) * partialTick;
            default:
                throw new IllegalArgumentException(MolangRenderDiagnostics.MSG_INVALID_POSITION_AXIS);
        }
    }

    public double positionDelta(int axis) {
        switch (axis) {
            case MolangRenderLayout.MAX_INITIAL_VALUE:
                return (x - previousX) * partialTick;
            case MolangRenderLayout.MIN_SINGLE_UNIT:
                return (y - previousY) * partialTick;
            case MolangRenderLayout.MOLANG_FRAMES_GET_EQUIPMENT_IN_SLOT_FIRST_PARAM_3:
                return (z - previousZ) * partialTick;
            default:
                throw new IllegalArgumentException(MolangRenderDiagnostics.MSG_INVALID_POSITION_AXIS);
        }
    }

    public void setEquipment(Slot slot, String itemId, int maxDamage, int damage) {
        setEquipment(slot, itemId, maxDamage, damage, "", MolangRenderIds.ID_NONE);
    }

    public void setEquipment(Slot slot, String itemId, int maxDamage, int damage, String classification,
        String useAnimation) {
        if (itemId == null) {
            equipment.remove(slot);
            return;
        }
        // 实际注册表数据不经高版本 ID 校验，避免旧模组的合法物品中断整帧动画
        String qualified = itemId.indexOf(':') < 0 ? MolangRenderIds.ID_MINECRAFT + itemId : itemId;
        equipment.put(slot, new Equipment(qualified, maxDamage, damage, classification, useAnimation));
    }

    public Equipment equipment(Slot slot) {
        return equipment.get(slot);
    }

    public static String canonicalItemId(String value) {
        if (value == null) return null;
        String result = value.indexOf(':') < 0 ? MolangRenderIds.ID_MINECRAFT + value : value;
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
        public final String classification, useAnimation;

        private Equipment(String itemId, int maxDamage, int damage, String classification, String useAnimation) {
            this.itemId = itemId;
            this.maxDamage = maxDamage;
            this.damage = damage;
            this.classification = Objects.requireNonNull(classification, MolangRenderIds.ID_CLASSIFICATION);
            this.useAnimation = Objects.requireNonNull(useAnimation, MolangRenderIds.ID_USEANIMATION);
        }
    }
}
