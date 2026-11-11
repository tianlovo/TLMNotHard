package com.tianluoqaq.tlmnh.common.ai.faithful.life;

/**
 * Candidate order from Minecraft 1.20.1 BedBlock.findStandUpPosition/bedStandUpOffsets
 * The caller supplies the actual bed facing and the yaw vector computed with native MathHelper
 * Native 1.7 support/collision/liquid checks remain in MaidModernLife; this is not DismountHelper
 */
final class MaidBedExitOffsets {

    // Bed metadata uses SOUTH, WEST, NORTH, EAST in both platform mappings
    private static final int[][] FACING = {
        { FaithfulLifeTuning.BED_EXIT_DEFAULT_ZERO_BASE, FaithfulLifeTuning.BED_EXIT_DEFAULT_UNIT_STEP },
        { FaithfulLifeTuning.UNSET_OR_NOT_FOUND, FaithfulLifeTuning.BED_EXIT_DEFAULT_ZERO_BASE },
        { FaithfulLifeTuning.BED_EXIT_DEFAULT_ZERO_BASE, FaithfulLifeTuning.UNSET_OR_NOT_FOUND },
        { FaithfulLifeTuning.BED_EXIT_DEFAULT_UNIT_STEP, FaithfulLifeTuning.BED_EXIT_DEFAULT_ZERO_BASE } };

    private MaidBedExitOffsets() {}

    static int[][] ordered(int facing, float lookX, float lookZ) {
        if (facing < 0 || facing >= FACING.length || !Float.isFinite(lookX) || !Float.isFinite(lookZ))
            return new int[0][];
        int[] forward = FACING[facing];
        int side = (facing + 1) & FaithfulLifeTuning.LOW_TWO_BITS_MASK;
        if (FACING[side][FaithfulLifeTuning.BED_EXIT_DEFAULT_ZERO_BASE] * lookX
            + FACING[side][FaithfulLifeTuning.BED_EXIT_DEFAULT_UNIT_STEP] * lookZ > 0)
            side = (side + FaithfulLifeTuning.BED_EXIT_SIDE) & FaithfulLifeTuning.LOW_TWO_BITS_MASK;
        int fx = forward[FaithfulLifeTuning.BED_EXIT_DEFAULT_ZERO_BASE],
            fz = forward[FaithfulLifeTuning.BED_EXIT_DEFAULT_UNIT_STEP],
            sx = FACING[side][FaithfulLifeTuning.BED_EXIT_DEFAULT_ZERO_BASE],
            sz = FACING[side][FaithfulLifeTuning.BED_EXIT_DEFAULT_UNIT_STEP];
        return new int[][] { { sx, sz }, { sx - fx, sz - fz },
            { sx - FaithfulLifeTuning.BED_EXIT_SIDE * fx, sz - FaithfulLifeTuning.BED_EXIT_SIDE * fz },
            { FaithfulLifeTuning.BED_EXIT_ORDERED_PARAM * fx, FaithfulLifeTuning.BED_EXIT_ORDERED_PARAM * fz },
            { -sx - FaithfulLifeTuning.BED_EXIT_SIDE * fx, -sz - FaithfulLifeTuning.BED_EXIT_SIDE * fz },
            { -sx - fx, -sz - fz }, { -sx, -sz }, { -sx + fx, -sz + fz }, { fx, fz }, { sx + fx, sz + fz },
            { FaithfulLifeTuning.BED_EXIT_DEFAULT_ZERO_BASE, FaithfulLifeTuning.BED_EXIT_DEFAULT_ZERO_BASE },
            { -fx, -fz } };
    }
}
