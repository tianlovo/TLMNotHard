package com.tianluoqaq.tlmnh.common.ai.faithful.life;

/**
 * Candidate order from Minecraft 1.20.1 BedBlock.findStandUpPosition/bedStandUpOffsets
 * The caller supplies the actual bed facing and the yaw vector computed with native MathHelper
 * Native 1.7 support/collision/liquid checks remain in MaidModernLife; this is not DismountHelper
 */
final class MaidBedExitOffsets {

    // Bed metadata uses SOUTH, WEST, NORTH, EAST in both platform mappings
    private static final int[][] FACING = { { 0, 1 }, { -1, 0 }, { 0, -1 }, { 1, 0 } };

    private MaidBedExitOffsets() {}

    static int[][] ordered(int facing, float lookX, float lookZ) {
        if (facing < 0 || facing >= FACING.length || !Float.isFinite(lookX) || !Float.isFinite(lookZ))
            return new int[0][];
        int[] forward = FACING[facing];
        int side = (facing + 1) & 3;
        if (FACING[side][0] * lookX + FACING[side][1] * lookZ > 0) side = (side + 2) & 3;
        int fx = forward[0], fz = forward[1], sx = FACING[side][0], sz = FACING[side][1];
        return new int[][] { { sx, sz }, { sx - fx, sz - fz }, { sx - 2 * fx, sz - 2 * fz }, { -2 * fx, -2 * fz },
            { -sx - 2 * fx, -sz - 2 * fz }, { -sx - fx, -sz - fz }, { -sx, -sz }, { -sx + fx, -sz + fz }, { fx, fz },
            { sx + fx, sz + fz }, { 0, 0 }, { -fx, -fz } };
    }
}
