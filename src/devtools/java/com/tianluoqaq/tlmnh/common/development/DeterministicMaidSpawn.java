package com.tianluoqaq.tlmnh.common.development;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/**
 * 为回归测试提供确定性的女仆初始化，消除rotation和SoundPackId的随机差异
 *
 * <p>
 * 仅用于开发测试fixture，不影响正常游戏spawn
 */
public final class DeterministicMaidSpawn {

    /** 固定的测试音色ID，避免随机选择导致两引擎差异 */
    public static final String FIXED_SOUND_PACK_ID = "touhou_little_maid";

    /** 固定的测试yaw角度，避免spawn随机朝向 */
    public static final float FIXED_YAW = 0.0f;

    /** 固定的测试pitch角度 */
    public static final float FIXED_PITCH = 0.0f;

    private DeterministicMaidSpawn() {}

    /**
     * 固定女仆的音色和朝向，用于回归测试的确定性输入
     *
     * @param maid 需要固定初始状态的女仆实体
     */
    public static void freezeInitialState(EntityMaid maid) {
        // 固定音色ID，覆盖entityInit中的随机选择
        maid.setSoundPackId(FIXED_SOUND_PACK_ID);

        // 固定rotation，覆盖spawn的随机朝向
        maid.rotationYaw = FIXED_YAW;
        maid.rotationYawHead = FIXED_YAW;
        maid.renderYawOffset = FIXED_YAW;
        maid.prevRotationYaw = FIXED_YAW;
        maid.prevRotationYawHead = FIXED_YAW;
        maid.prevRenderYawOffset = FIXED_YAW;

        maid.rotationPitch = FIXED_PITCH;
        maid.prevRotationPitch = FIXED_PITCH;
    }

    /**
     * 检查系统属性，判断是否应该为回归测试固定初始状态
     *
     * @return 如果需要确定性spawn则返回true
     */
    public static boolean isDeterministicSpawnEnabled() {
        return Boolean.getBoolean("tlmnh.deterministicSpawn");
    }
}
