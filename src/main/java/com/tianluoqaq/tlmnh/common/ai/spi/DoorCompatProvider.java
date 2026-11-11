package com.tianluoqaq.tlmnh.common.ai.spi;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.DoorCell;

/** 门与栅栏门的状态读写及战斗碰撞几何契约；未安装外部动画门模组时按原版门处理 */
public interface DoorCompatProvider {

    DoorCompatProvider NOOP = new DoorCompatProvider() {};

    enum GeometryResult {
        SAFE,
        UNKNOWN,
        BUDGET
    }

    final class GeometrySample {

        public static final GeometrySample UNKNOWN = new GeometrySample(GeometryResult.UNKNOWN, null, null);
        public static final GeometrySample BUDGET = new GeometrySample(GeometryResult.BUDGET, null, null);

        public final GeometryResult result;
        public final AxisAlignedBB body;
        public final DoorCell doorCell;

        public GeometrySample(GeometryResult result, AxisAlignedBB body, DoorCell doorCell) {
            this.result = result;
            this.body = body;
            this.doorCell = doorCell;
        }
    }

    default boolean isDoorOpen(Block block, World world, int x, int y, int z) {
        return block instanceof BlockDoor door && (door.func_150012_g(world, x, y, z) & 4) != 0;
    }

    default boolean setDoorOpen(Block block, World world, int x, int y, int z, boolean open) {
        if (!(block instanceof BlockDoor door)) return false;
        door.func_150014_a(world, x, y, z, open);
        return true;
    }

    default boolean isDoorOpenOrOpening(BlockDoor door, World world, int x, int y, int z) {
        return door != null && (door.func_150012_g(world, x, y, z) & 4) != 0;
    }

    default boolean supportsCombatGeometry(Block block) {
        return false;
    }

    default GeometrySample captureCombatGeometry(World world, Block block, int x, int y, int z, CombatWorkQuota quota) {
        return GeometrySample.UNKNOWN;
    }
}
