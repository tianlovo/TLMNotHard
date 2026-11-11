package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain;

import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota.Work;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.DoorCell;
import com.tianluoqaq.tlmnh.common.ai.spi.DoorCompatProvider;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.common.Loader;

/** 当前世界的窄空间复核；未知提供方与不足额度从不转成净空 */
public final class CombatSpace {

    public enum Result {
        SAFE,
        BLOCKED,
        UNKNOWN,
        BUDGET
    }

    private static final Set<String> FULL = names(
        CombatTerrainDiagnostics.MSG_BLOCK_BLOCKSTONE_BLOCKGRASS_BLOCKDIRT_BLOCKGRAVE
            + CombatTerrainDiagnostics.MSG_BLOCKSANDSTONE_BLOCKCLAY_BLOCKNETHERRACK_BLOCKOR
            + CombatTerrainDiagnostics.MSG_BLOCKCOMPRESSEDPOWERED_BLOCKSTONEBRICK_BLOCKOBSI
            + CombatTerrainDiagnostics.MSG_BLOCKHARDENEDCLAY_BLOCKCOLORED_BLOCKBOOKSHELF_BL
            + CombatTerrainDiagnostics.MSG_BLOCKPACKEDICE_BLOCKSNOWBLOCK_BLOCKFURNACE_BLOCK
            + CombatTerrainDiagnostics.MSG_BLOCKNOTE_BLOCKTNT_BLOCKREDSTONELIGHT_BLOCKCOMMA);
    private static final Set<String> LOCAL = names(
        CombatTerrainDiagnostics.MSG_BLOCKSTONESLAB_BLOCKWOODSLAB_BLOCKDOOR_BLOCKTRAP
            + CombatTerrainDiagnostics.MSG_BLOCKFENCEGATE_BLOCKSNOW);
    private static final Set<String> NEIGHBOR = names(
        CombatTerrainDiagnostics.MSG_BLOCKPANE_BLOCKSTAINEDGLASSPANE_BLOCKSTAIRS_BLOC);
    private static final Set<String> RAY_CUBES = names(
        CombatTerrainDiagnostics.MSG_BLOCKSTONE_BLOCKDIRT_BLOCKGLASS_BLOCKSTAINEDGLAS);
    private static final ClassValue<Class<?>> MAID_COLLISION = new ClassValue<Class<?>>() {

        @Override
        protected Class<?> computeValue(Class<?> type) {
            Class<?> declared = owner(
                type,
                CombatTerrainIds.ID_GETCOLLISIONBOX,
                CombatTerrainReflectionSpec.SRG_MEMBER_FUNC_70114_G,
                Entity.class);
            return declared == null ? Void.class : declared;
        }
    };
    private static final ClassValue<EntityCollision> ENTITY_COLLISION = new ClassValue<EntityCollision>() {

        @Override
        protected EntityCollision computeValue(Class<?> type) {
            Class<?> box = owner(
                type,
                CombatTerrainIds.ID_GETBOUNDINGBOX,
                CombatTerrainReflectionSpec.SRG_MEMBER_FUNC_70046_E);
            Class<?> parts = owner(
                type,
                CombatTerrainIds.ID_GETPARTS,
                CombatTerrainReflectionSpec.SRG_MEMBER_FUNC_70021_AL);
            int kind = parts != Entity.class ? CombatTerrainTuning.UNSET_OR_NOT_FOUND
                : box == Entity.class ? CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE
                    : box != null && box.getName()
                        .equals(CombatTerrainReflectionSpec.TARGET_CLASS_ENTITY_BOAT)
                            ? CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP
                            : CombatTerrainTuning.UNSET_OR_NOT_FOUND;
            return new EntityCollision(kind, box, parts);
        }
    };

    private CombatSpace() {}

    /** 只记录当前调用已经取得的结果，不补查世界，也不参与执行许可 */
    public static final class Diagnostic {

        public Result result;
        public String reason = CombatTerrainIds.ID_NOT_CHECKED, maidOwner = CombatTerrainIds.ID_UNQUERIED,
            blockType = CombatTerrainIds.ID_UNQUERIED, entityType = CombatTerrainIds.ID_NONE,
            entityBoxOwner = CombatTerrainIds.ID_UNQUERIED, entityPartsOwner = CombatTerrainIds.ID_UNQUERIED;
        public long tick;
        public boolean block;
        public int x, y, z;
        public double radius = Double.NaN;

        private void reset(long at) {
            tick = at;
            result = null;
            reason = CombatTerrainIds.ID_NOT_CHECKED;
            maidOwner = blockType = entityBoxOwner = entityPartsOwner = CombatTerrainIds.ID_UNQUERIED;
            entityType = CombatTerrainIds.ID_NONE;
            block = false;
            radius = Double.NaN;
        }

        private void atBlock(int px, int py, int pz, BlockReader reader) {
            block = true;
            x = px;
            y = py;
            z = pz;
            blockType = reader.lastBlockType;
        }

        public String summary() {
            return result + CombatTerrainIds.PATH_SLASH_SEPARATOR
                + reason
                + CombatTerrainIds.COMBAT_SPACE_SUMMARY_MESSAGE
                + tick
                + CombatTerrainIds.ID_MAID
                + maidOwner
                + CombatTerrainIds.ID_RADIUS
                + radius
                + (block
                    ? CombatTerrainIds.ID_BLOCK + x
                        + CombatTerrainIds.COMMA_DELIMITER
                        + y
                        + CombatTerrainIds.COMMA_DELIMITER
                        + z
                        + CombatTerrainIds.PATH_SLASH_SEPARATOR
                        + blockType
                    : "")
                + CombatTerrainIds.ID_ENTITY
                + entityType
                + CombatTerrainIds.ID_BOXOWNER
                + entityBoxOwner
                + CombatTerrainIds.ID_PARTSOWNER
                + entityPartsOwner;
        }
    }

    private static final class EntityCollision {

        final int kind;
        final Class<?> box, parts;

        EntityCollision(int kind, Class<?> box, Class<?> parts) {
            this.kind = kind;
            this.box = box;
            this.parts = parts;
        }
    }

    private static Result recorded(Diagnostic diagnostic, Result result, String reason) {
        if (diagnostic != null) {
            diagnostic.result = result;
            diagnostic.reason = reason;
        }
        return result;
    }

    private static String typeName(Class<?> type) {
        return type == null || type == Void.class ? CombatTerrainIds.ID_UNRESOLVED : type.getName();
    }

    /** x/y/z是实际脚底中心，不是方块索引 */
    public static Result safeStandingPoint(EntityMaid maid, double x, double y, double z, CombatWorkQuota quota) {
        return safeStandingPoint(maid, x, y, z, quota, null);
    }

    public static Result safeStandingPoint(EntityMaid maid, double x, double y, double z, CombatWorkQuota quota,
        Diagnostic diagnostic) {
        if (diagnostic != null) diagnostic.reset(quota.tick);
        if (!finite(x, y, z)) return recorded(diagnostic, Result.UNKNOWN, CombatTerrainIds.ID_INVALID_POINT);
        double half = maid.width * CombatTerrainTuning.COMBAT_CONDITIONAL_ABS_FIRST_FACTOR;
        AxisAlignedBB body = AxisAlignedBB.getBoundingBox(
            x - half + CombatTerrainTuning.EPSILON,
            y + CombatTerrainTuning.COMBAT_SPACE_DOMAIN_FACTOR,
            z - half + CombatTerrainTuning.EPSILON,
            x + half - CombatTerrainTuning.EPSILON,
            y + maid.height - CombatTerrainTuning.COMBAT_SPACE_DOMAIN_FACTOR,
            z + half - CombatTerrainTuning.EPSILON);
        Result clear = boundedClear(maid, body, quota, diagnostic);
        if (clear != Result.SAFE) return clear;
        return standingSupport(maid, x, y, z, quota, diagnostic);
    }

    /** 仅本次跳跃复核复用已覆盖当前身体的头顶扫掠；不跨调用或tick保存许可 */
    public static Result safeJumpStandingPoint(EntityMaid maid, AxisAlignedBB sweep, CombatWorkQuota quota,
        Diagnostic head, Diagnostic support) {
        if (head != null) head.reset(quota.tick);
        if (support != null) support.reset(quota.tick);
        World checkedWorld = maid.worldObj;
        int checkedEntity = maid.getEntityId();
        double x = maid.posX, y = maid.boundingBox.minY, z = maid.posZ;
        float width = maid.width, height = maid.height;
        AxisAlignedBB actual = maid.boundingBox;
        double minX = actual.minX, minY = actual.minY, minZ = actual.minZ;
        double maxX = actual.maxX, maxY = actual.maxY, maxZ = actual.maxZ;
        double half = width * CombatTerrainTuning.COMBAT_CONDITIONAL_ABS_FIRST_FACTOR;
        // 包含safeStandingPoint原先检查的同一body；更大的sweep仍遵守boundedClear原体积上限
        if (!valid(sweep) || !finite(x, y, z)
            || sweep.minX > x - half + CombatTerrainTuning.EPSILON
            || sweep.maxX < x + half - CombatTerrainTuning.EPSILON
            || sweep.minY > y + CombatTerrainTuning.COMBAT_SPACE_DOMAIN_FACTOR
            || sweep.maxY < y + height - CombatTerrainTuning.COMBAT_SPACE_DOMAIN_FACTOR
            || sweep.minZ > z - half + CombatTerrainTuning.EPSILON
            || sweep.maxZ < z + half - CombatTerrainTuning.EPSILON)
            return recorded(head, Result.UNKNOWN, CombatTerrainIds.ID_JUMP_SWEEP_DOES_NOT_COVER_BODY);
        Result clear = boundedClear(maid, sweep, quota, head);
        if (clear != Result.SAFE) return clear;
        AxisAlignedBB current = maid.boundingBox;
        if (maid.worldObj != checkedWorld || checkedWorld.getTotalWorldTime() != quota.tick
            || maid.getEntityId() != checkedEntity
            || maid.posX != x
            || current.minY != y
            || maid.posZ != z
            || maid.width != width
            || maid.height != height
            || current.minX != minX
            || current.minY != minY
            || current.minZ != minZ
            || current.maxX != maxX
            || current.maxY != maxY
            || current.maxZ != maxZ)
            return recorded(support, Result.UNKNOWN, CombatTerrainIds.ID_JUMP_BODY_CHANGED_AFTER_SWEEP);
        return standingSupport(maid, x, y, z, quota, support);
    }

    private static Result standingSupport(EntityMaid maid, double x, double y, double z, CombatWorkQuota quota,
        Diagnostic diagnostic) {
        double half = maid.width * CombatTerrainTuning.COMBAT_CONDITIONAL_ABS_FIRST_FACTOR;
        int minX = floor(x - half), maxX = floor(x + half), minZ = floor(z - half), maxZ = floor(z + half);
        int top = floor(y - CombatTerrainTuning.EPSILON);
        BlockReader reader = reader(maid, minX, top - 1, minZ, maxX, top, maxZ, quota);
        if (reader.result != Result.SAFE)
            return recorded(diagnostic, reader.result, CombatTerrainIds.ID_SUPPORT_READER);
        for (int dx = CombatTerrainTuning.FOOTPRINT_CORNER_MIN_DIRECTION; dx
            <= CombatTerrainTuning.FOOTPRINT_CORNER_MAX_DIRECTION; dx += CombatTerrainTuning.FOOTPRINT_CORNER_DIRECTION_STRIDE)
            for (int dz = CombatTerrainTuning.FOOTPRINT_CORNER_MIN_DIRECTION; dz
                <= CombatTerrainTuning.FOOTPRINT_CORNER_MAX_DIRECTION; dz += CombatTerrainTuning.FOOTPRINT_CORNER_DIRECTION_STRIDE) {
                    double px = x + dx * half * CombatTerrainTuning.COMBAT_SPACE_LAYOUT_PX_OFFSET,
                        pz = z + dz * half * CombatTerrainTuning.COMBAT_SPACE_LAYOUT_PX_OFFSET;
                    boolean supported = false;
                    for (int py = top; py >= top - 1; py--) {
                        BlockShapes shapes = reader.block(floor(px), py, floor(pz), cellMask(floor(px), py, floor(pz)));
                        if (shapes.result != Result.SAFE) {
                            if (diagnostic != null) diagnostic.atBlock(floor(px), py, floor(pz), reader);
                            return recorded(diagnostic, shapes.result, CombatTerrainIds.ID_SUPPORT_QUERY);
                        }
                        if ((shapes.flags & (CombatTerrainTuning.HAZARD | CombatTerrainTuning.WATER)) != 0) {
                            if (diagnostic != null) diagnostic.atBlock(floor(px), py, floor(pz), reader);
                            return recorded(diagnostic, Result.BLOCKED, CombatTerrainIds.ID_SUPPORT_HAZARD);
                        }
                        for (int i = 0; i < shapes.count; i++) {
                            if (!quota.spend(Work.COLLISION)) return recorded(
                                diagnostic,
                                Result.BUDGET,
                                CombatTerrainIds.ID_SUPPORT_COLLISION_BUDGET);
                            int offset = i * CombatTerrainTuning.COMBAT_SNAPSHOT_DOMAIN_PARAM;
                            if (px >= shapes.boxes[offset] - CombatTerrainTuning.EPSILON
                                && px <= shapes.boxes[offset + CombatTerrainTuning.COMBAT_MOVEMENT_DOMAIN_PARAM]
                                    + CombatTerrainTuning.EPSILON
                                && pz >= shapes.boxes[offset + CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM]
                                    - CombatTerrainTuning.EPSILON
                                && pz <= shapes.boxes[offset + CombatTerrainTuning.COMBAT_SPACE_DOMAIN_PARAM]
                                    + CombatTerrainTuning.EPSILON
                                && shapes.boxes[offset + CombatTerrainTuning.COMBAT_SPACE_DOMAIN_PARAM_2]
                                    >= y - CombatTerrainTuning.COMBAT_SPACE_DOMAIN_FACTOR_2
                                && shapes.boxes[offset + CombatTerrainTuning.COMBAT_SPACE_DOMAIN_PARAM_2]
                                    <= y + CombatTerrainTuning.COMBAT_SPACE_DOMAIN_FACTOR_3) {
                                supported = true;
                                break;
                            }
                        }
                        if (supported) break;
                    }
                    if (!supported) {
                        if (diagnostic != null) diagnostic.atBlock(floor(px), top - 1, floor(pz), reader);
                        return recorded(diagnostic, Result.BLOCKED, CombatTerrainIds.ID_MISSING_SUPPORT);
                    }
                }
        return recorded(diagnostic, Result.SAFE, CombatTerrainIds.ID_SUPPORTED);
    }

    /** 完整扫完方块与原生实体碰撞的有界候选才返回SAFE */
    public static Result boundedClear(EntityMaid maid, AxisAlignedBB box, CombatWorkQuota quota) {
        return boundedClear(maid, box, quota, null);
    }

    public static Result boundedClear(EntityMaid maid, AxisAlignedBB box, CombatWorkQuota quota,
        Diagnostic diagnostic) {
        if (diagnostic != null) diagnostic.reset(quota.tick);
        Class<?> collisionOwner = MAID_COLLISION.get(maid.getClass());
        if (diagnostic != null) diagnostic.maidOwner = typeName(collisionOwner);
        if (collisionOwner != Entity.class)
            return recorded(diagnostic, Result.UNKNOWN, CombatTerrainIds.ID_MAID_COLLISION_OWNER);
        if (!valid(box)) return recorded(diagnostic, Result.UNKNOWN, CombatTerrainIds.ID_INVALID_BOX);
        if (!quota.spend(Work.FINAL_CHECK))
            return recorded(diagnostic, Result.BUDGET, CombatTerrainIds.ID_FINAL_CHECK_BUDGET);
        int minX = floor(box.minX), maxX = floor(box.maxX - CombatTerrainTuning.EPSILON);
        int minY = floor(box.minY) - 1, maxY = floor(box.maxY - CombatTerrainTuning.EPSILON);
        int minZ = floor(box.minZ), maxZ = floor(box.maxZ - CombatTerrainTuning.EPSILON);
        long volume = volume(minX, minY, minZ, maxX, maxY, maxZ);
        if (volume < 0 || volume > CombatTerrainTuning.MAX_BLOCKS
            || maxX - minX > CombatTerrainTuning.MAX_MIN_X_THRESHOLD
            || maxY - minY > CombatTerrainTuning.MAX_MIN_X_THRESHOLD
            || maxZ - minZ > CombatTerrainTuning.MAX_MIN_X_THRESHOLD)
            return recorded(diagnostic, Result.UNKNOWN, CombatTerrainIds.ID_BLOCK_BOUNDS_LIMIT);
        BlockReader reader = reader(maid, minX, minY, minZ, maxX, maxY, maxZ, quota);
        if (reader.result != Result.SAFE) return recorded(diagnostic, reader.result, CombatTerrainIds.ID_CLEAR_READER);
        for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
            BlockShapes shapes = reader.block(x, y, z, box);
            if (shapes.result != Result.SAFE) {
                if (diagnostic != null) diagnostic.atBlock(x, y, z, reader);
                return recorded(diagnostic, shapes.result, CombatTerrainIds.ID_CLEAR_BLOCK_QUERY);
            }
            if ((shapes.flags & (CombatTerrainTuning.HAZARD | CombatTerrainTuning.WATER)) != 0
                && box.intersectsWith(AxisAlignedBB.getBoundingBox(x, y, z, x + 1, y + 1, z + 1))) {
                if (diagnostic != null) diagnostic.atBlock(x, y, z, reader);
                return recorded(diagnostic, Result.BLOCKED, CombatTerrainIds.ID_CLEAR_HAZARD);
            }
            if (shapes.count > 0) {
                if (diagnostic != null) diagnostic.atBlock(x, y, z, reader);
                return recorded(diagnostic, Result.BLOCKED, CombatTerrainIds.ID_CLEAR_BLOCK_COLLISION);
            }
        }
        return entitiesClear(maid, box, quota, diagnostic);
    }

    /** 一次已加载区块预检可供整个小范围复用，避免每个方块重复查询区块 */
    public static BlockReader reader(EntityMaid maid, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
        CombatWorkQuota quota) {
        return new BlockReader(maid, minX, minY, minZ, maxX, maxY, maxZ, quota);
    }

    public static final class BlockReader {

        public final Result result;
        private final EntityMaid maid;
        private final World world;
        private final long tick;
        private final CombatWorkQuota quota;
        private final int minX, minY, minZ, maxX, maxY, maxZ;
        private String lastBlockType = CombatTerrainIds.ID_UNQUERIED;

        private BlockReader(EntityMaid maid, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            CombatWorkQuota quota) {
            this.maid = maid;
            world = maid.worldObj;
            tick = world.getTotalWorldTime();
            this.quota = quota;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            long count = volume(minX, minY, minZ, maxX, maxY, maxZ);
            if (count < 0 || count > CombatTerrainTuning.MAX_LENGTH_THRESHOLD
                || minY < 0
                || maxY >= world.getHeight()
                || minX < CombatTerrainTuning.MIN_MIN_X_THRESHOLD
                || maxX > CombatTerrainTuning.MAX_MAX_X_THRESHOLD
                || minZ < CombatTerrainTuning.MIN_MIN_X_THRESHOLD
                || maxZ > CombatTerrainTuning.MAX_MAX_X_THRESHOLD) {
                result = Result.UNKNOWN;
                return;
            }
            result = loaded(world, minX - 1, minZ - 1, maxX + 1, maxZ + 1, quota);
        }

        public BlockShapes block(int x, int y, int z, AxisAlignedBB mask) {
            lastBlockType = CombatTerrainIds.ID_UNQUERIED;
            if (result != Result.SAFE) return failed(result);
            if (maid.worldObj != world || world.getTotalWorldTime() != tick
                || quota.tick != tick
                || x < minX
                || x > maxX
                || y < minY
                || y > maxY
                || z < minZ
                || z > maxZ
                || !valid(mask)) return failed(Result.UNKNOWN);
            if (!quota.spend(Work.BLOCK)) return failed(Result.BUDGET);
            Block block = world.getBlock(x, y, z);
            lastBlockType = block.getClass()
                .getName();
            if (CompatServices.doors()
                .supportsCombatGeometry(block)) {
                var gate = CompatServices.doors()
                    .captureCombatGeometry(world, block, x, y, z, quota);
                if (gate.result != DoorCompatProvider.GeometryResult.SAFE) return failed(
                    gate.result == DoorCompatProvider.GeometryResult.BUDGET ? Result.BUDGET : Result.UNKNOWN);
                ShapeList shapes = new ShapeList(quota);
                try {
                    if (gate.body != null && gate.body.intersectsWith(mask)) shapes.add(gate.body);
                    return shapes.finish(
                        CombatTerrainTuning.RAY_UNKNOWN | ((block == Blocks.wooden_door ? maid.getConfigManager()
                            .isOpenDoor()
                            : maid.getConfigManager()
                                .isOpenFenceGate()) ? CombatTerrainTuning.OPENABLE
                                    : CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE),
                        gate.doorCell);
                } catch (Limit limit) {
                    return failed(limit.result);
                }
            }
            int kind = kind(block), flags = flags(block);
            if (kind < 0) return failed(Result.UNKNOWN);
            String nativeName = block.getClass()
                .getName();
            // 多shape外包仅可保守拒绝移动；只有已核射线单盒才能提供遮挡保护
            if (RAY_CUBES.contains(nativeName)
                || nativeName.equals(CombatTerrainReflectionSpec.TARGET_CLASS_BLOCK_STONE_SLAB)
                || nativeName.equals(CombatTerrainReflectionSpec.TARGET_CLASS_BLOCK_WOOD_SLAB)
                || nativeName.equals(CombatTerrainReflectionSpec.TARGET_CLASS_BLOCK_DOOR)
                || nativeName.equals(CombatTerrainReflectionSpec.TARGET_CLASS_BLOCK_TRAP_DOOR))
                flags |= CombatTerrainTuning.RAY_BLOCKING;
            else if (block != Blocks.air) flags |= CombatTerrainTuning.RAY_UNKNOWN;
            // 只标记已核shape提供方及自身配置，不新增方块/metadata/邻接查询
            if (block == Blocks.wooden_door && maid.getConfigManager()
                .isOpenDoor() || block == Blocks.fence_gate
                    && maid.getConfigManager()
                        .isOpenFenceGate())
                flags |= CombatTerrainTuning.OPENABLE;
            if (kind == 0) return new BlockShapes(
                Result.SAFE,
                flags,
                new double[0],
                CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE);
            ShapeList shapes = new ShapeList(quota);
            if (kind == CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP || block == Blocks.soul_sand) {
                double height = block == Blocks.soul_sand ? CombatTerrainTuning.COMBAT_SPACE_BLOCK_FACTOR
                    : CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP;
                AxisAlignedBB cube = AxisAlignedBB.getBoundingBox(x, y, z, x + 1, y + height, z + 1);
                if (cube.intersectsWith(mask)) {
                    try {
                        shapes.add(cube);
                    } catch (Limit limit) {
                        return failed(limit.result);
                    }
                }
                return shapes.finish(flags);
            }
            // 已读原生实现只有有界的本格/一格邻接读取；为真实提供方预留已核上界
            int queryAllowance = queryAllowance(block);
            if (!quota.spend(Work.BLOCK, queryAllowance)) return failed(Result.BUDGET);
            if (needsNeighborAdmission(block)) {
                int[][] offsets = {
                    { CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE, CombatTerrainTuning.UNSET_OR_NOT_FOUND },
                    { CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE,
                        CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP },
                    { CombatTerrainTuning.UNSET_OR_NOT_FOUND, CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE },
                    { CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP,
                        CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE } };
                for (int[] offset : offsets) {
                    if (!quota.spend(Work.BLOCK)) return failed(Result.BUDGET);
                    if (kind(
                        world.getBlock(
                            x + offset[CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE],
                            y,
                            z + offset[CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP]))
                        < 0) return failed(Result.UNKNOWN);
                }
            }
            float a = (float) block.getBlockBoundsMinX(), b = (float) block.getBlockBoundsMinY(),
                c = (float) block.getBlockBoundsMinZ(), d = (float) block.getBlockBoundsMaxX(),
                e = (float) block.getBlockBoundsMaxY(), f = (float) block.getBlockBoundsMaxZ();
            try {
                // 清掉共享遗留bounds；多形状提供方会按当前状态逐片提交
                block.setBlockBounds(
                    CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE,
                    CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE,
                    CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE,
                    CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP,
                    CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP,
                    CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP);
                block.addCollisionBoxesToList(world, x, y, z, mask, shapes, maid);
                return shapes.finish(flags);
            } catch (Limit limit) {
                return failed(limit.result);
            } catch (Throwable failure) {
                IntegrationGuard.throwIfFatal(failure);
                return failed(Result.UNKNOWN);
            } finally {
                block.setBlockBounds(a, b, c, d, e, f);
            }
        }
    }

    public static final class BlockShapes {

        public final Result result;
        public final int flags, count;
        public final double minX, minY, minZ, maxX, maxY, maxZ;
        public final DoorCell doorCell;
        private final double[] boxes;

        private BlockShapes(Result result, int flags, double[] boxes, int count) {
            this(result, flags, boxes, count, null);
        }

        private BlockShapes(Result result, int flags, double[] boxes, int count, DoorCell doorCell) {
            this.result = result;
            this.flags = flags;
            this.count = count;
            this.boxes = boxes;
            this.doorCell = doorCell;
            double ax = Double.POSITIVE_INFINITY, ay = ax, az = ax, bx = Double.NEGATIVE_INFINITY, by = bx, bz = bx;
            for (int i = 0; i < count; i++) {
                int offset = i * CombatTerrainTuning.COMBAT_SNAPSHOT_DOMAIN_PARAM;
                ax = Math.min(ax, boxes[offset]);
                ay = Math.min(ay, boxes[offset + 1]);
                az = Math.min(az, boxes[offset + CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM]);
                bx = Math.max(bx, boxes[offset + CombatTerrainTuning.COMBAT_MOVEMENT_DOMAIN_PARAM]);
                by = Math.max(by, boxes[offset + CombatTerrainTuning.COMBAT_SPACE_DOMAIN_PARAM_2]);
                bz = Math.max(bz, boxes[offset + CombatTerrainTuning.COMBAT_SPACE_DOMAIN_PARAM]);
            }
            minX = ax;
            minY = ay;
            minZ = az;
            maxX = bx;
            maxY = by;
            maxZ = bz;
        }
    }

    private static Result entitiesClear(EntityMaid maid, AxisAlignedBB box, CombatWorkQuota quota,
        Diagnostic diagnostic) {
        double radius = World.MAX_ENTITY_RADIUS;
        if (diagnostic != null) diagnostic.radius = radius;
        if (!Double.isFinite(radius) || radius < 0 || radius > CombatTerrainTuning.COMBAT_SPACE_DOMAIN_PARAM_2)
            return recorded(diagnostic, Result.UNKNOWN, CombatTerrainIds.ID_ENTITY_RADIUS_BOUND);
        AxisAlignedBB broad = box.expand(
            CombatTerrainTuning.COMBAT_SPACE_EXPAND_FIRST_FACTOR,
            CombatTerrainTuning.COMBAT_SPACE_EXPAND_FIRST_FACTOR,
            CombatTerrainTuning.COMBAT_SPACE_EXPAND_FIRST_FACTOR);
        int minX = floor((broad.minX - radius) / CombatTerrainTuning.COMBAT_SPACE_MIN_X),
            maxX = floor((broad.maxX + radius) / CombatTerrainTuning.COMBAT_SPACE_MIN_X);
        int minZ = floor((broad.minZ - radius) / CombatTerrainTuning.COMBAT_SPACE_MIN_X),
            maxZ = floor((broad.maxZ + radius) / CombatTerrainTuning.COMBAT_SPACE_MIN_X);
        if ((maxX - minX + 1) * (maxZ - minZ + 1) > CombatTerrainTuning.COMBAT_SPACE_MIN_X)
            return recorded(diagnostic, Result.UNKNOWN, CombatTerrainIds.ID_ENTITY_CHUNK_LIMIT);
        for (int cx = minX; cx <= maxX; cx++) for (int cz = minZ; cz <= maxZ; cz++) {
            if (!quota.spend(Work.CHUNK))
                return recorded(diagnostic, Result.BUDGET, CombatTerrainIds.ID_ENTITY_CHUNK_BUDGET);
            if (!maid.worldObj.getChunkProvider()
                .chunkExists(cx, cz))
                return recorded(diagnostic, Result.UNKNOWN, CombatTerrainIds.ID_ENTITY_CHUNK_UNLOADED);
            var chunk = maid.worldObj.getChunkFromChunkCoords(cx, cz);
            int low = Math.max(
                CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE,
                floor((broad.minY - radius) / CombatTerrainTuning.COMBAT_SPACE_MIN_X));
            int high = Math.min(
                chunk.entityLists.length - 1,
                floor((broad.maxY + radius) / CombatTerrainTuning.COMBAT_SPACE_MIN_X));
            for (int section = low; section <= high; section++) {
                List<?> entities = chunk.entityLists[section];
                for (int index = 0; index < entities.size(); index++) {
                    if (!quota.spend(Work.ENTITY))
                        return recorded(diagnostic, Result.BUDGET, CombatTerrainIds.ID_ENTITY_PARTITION_BUDGET);
                    Object value = entities.get(index);
                    if (!(value instanceof Entity entity) || entity == maid
                        || entity.isDead
                        || entity.worldObj != maid.worldObj
                        || entity.boundingBox == null
                        || !entity.boundingBox.intersectsWith(broad)) continue;
                    EntityCollision collision = ENTITY_COLLISION.get(entity.getClass());
                    if (diagnostic != null) {
                        diagnostic.entityType = entity.getClass()
                            .getName();
                        diagnostic.entityBoxOwner = typeName(collision.box);
                        diagnostic.entityPartsOwner = typeName(collision.parts);
                    }
                    if (collision.kind < 0)
                        return recorded(diagnostic, Result.UNKNOWN, CombatTerrainIds.ID_ENTITY_COLLISION_OWNER);
                    // Entity返回null；Boat只返回public boundingBox；不调用未知覆盖或Minecart动态handler
                    if (collision.kind > 0 && entity.boundingBox.intersectsWith(box))
                        return recorded(diagnostic, Result.BLOCKED, CombatTerrainIds.ID_ENTITY_COLLISION);
                }
            }
        }
        return recorded(diagnostic, Result.SAFE, CombatTerrainIds.ID_CLEAR);
    }

    private static Result loaded(World world, int minX, int minZ, int maxX, int maxZ, CombatWorkQuota quota) {
        for (int x = minX >> CombatTerrainTuning.COMBAT_SPACE_DOMAIN_PARAM_2; x
            <= maxX >> CombatTerrainTuning.COMBAT_SPACE_DOMAIN_PARAM_2; x++)
            for (int z = minZ >> CombatTerrainTuning.COMBAT_SPACE_DOMAIN_PARAM_2; z
                <= maxZ >> CombatTerrainTuning.COMBAT_SPACE_DOMAIN_PARAM_2; z++) {
                    if (!quota.spend(Work.CHUNK)) return Result.BUDGET;
                    if (!world.getChunkProvider()
                        .chunkExists(x, z)) return Result.UNKNOWN;
                }
        return Result.SAFE;
    }

    private static int kind(Block block) {
        String name = block.getClass()
            .getName();
        if (auditedGtCube(name)) return CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP;
        Object registered = Block.blockRegistry.getNameForObject(block);
        if (!(registered instanceof String id) || !id.startsWith(CombatTerrainIds.ID_MINECRAFT)
            || !name.startsWith(CombatTerrainReflectionSpec.TARGET_CLASS))
            return CombatTerrainTuning.UNCLASSIFIED_BLOCK_FLAGS;
        if (empty(block)) return CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE;
        if (FULL.contains(name)) return CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP;
        if (LOCAL.contains(name) || NEIGHBOR.contains(name) || block == Blocks.soul_sand)
            return CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM;
        return CombatTerrainTuning.UNCLASSIFIED_BLOCK_FLAGS;
    }

    private static boolean auditedGtCube(String name) {
        if (!name.equals(CombatTerrainReflectionSpec.TARGET_CLASS_BLOCK_CONCRETES)
            && !name.equals(CombatTerrainReflectionSpec.TARGET_CLASS_BLOCK_STONES)) return false;
        var mod = Loader.instance()
            .getIndexedModList()
            .get(CombatTerrainIds.ID_GREGTECH);
        return mod != null && CombatTerrainIds.ID_5_09_54_265.equals(mod.getVersion());
    }

    private static int queryAllowance(Block block) {
        String name = block.getClass()
            .getName();
        if (name.endsWith(CombatTerrainIds.KEY_BLOCK_PANE)
            || name.endsWith(CombatTerrainIds.KEY_BLOCK_STAINED_GLASS_PANE)
            || name.endsWith(CombatTerrainIds.KEY_BLOCK_STAIRS))
            return CombatTerrainTuning.COMBAT_SPACE_QUERY_ALLOWANCE_PARAM;
        if (name.endsWith(CombatTerrainIds.KEY_BLOCK_FENCE) || name.endsWith(CombatTerrainIds.KEY_BLOCK_WALL))
            return CombatTerrainTuning.COMBAT_SPACE_DOMAIN_PARAM_2;
        return name.endsWith(CombatTerrainIds.KEY_BLOCK_DOOR) ? CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM
            : CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP;
    }

    private static boolean needsNeighborAdmission(Block block) {
        String name = block.getClass()
            .getName();
        return NEIGHBOR.contains(name) && !name.equals(CombatTerrainReflectionSpec.TARGET_CLASS_BLOCK_STAIRS);
    }

    private static boolean empty(Block block) {
        return block == Blocks.air || block == Blocks.water
            || block == Blocks.flowing_water
            || block == Blocks.lava
            || block == Blocks.flowing_lava
            || block == Blocks.fire
            || block == Blocks.tallgrass
            || block == Blocks.deadbush
            || block == Blocks.yellow_flower
            || block == Blocks.red_flower
            || block == Blocks.brown_mushroom
            || block == Blocks.red_mushroom
            || block == Blocks.sapling
            || block == Blocks.torch
            || block == Blocks.redstone_torch
            || block == Blocks.unlit_redstone_torch
            || block == Blocks.redstone_wire
            || block == Blocks.wheat
            || block == Blocks.carrots
            || block == Blocks.potatoes
            || block == Blocks.pumpkin_stem
            || block == Blocks.melon_stem
            || block == Blocks.reeds
            || block == Blocks.nether_wart
            || block == Blocks.rail
            || block == Blocks.golden_rail
            || block == Blocks.detector_rail
            || block == Blocks.activator_rail
            || block == Blocks.ladder
            || block == Blocks.vine
            || block == Blocks.tripwire
            || block == Blocks.tripwire_hook
            || block == Blocks.standing_sign
            || block == Blocks.wall_sign
            || block == Blocks.portal
            || block == Blocks.end_portal
            || block == Blocks.double_plant
            || block == Blocks.web;
    }

    private static int flags(Block block) {
        if (block == Blocks.water || block == Blocks.flowing_water) return CombatTerrainTuning.WATER;
        if (block == Blocks.lava || block == Blocks.flowing_lava
            || block == Blocks.fire
            || block == Blocks.cactus
            || block == Blocks.web) return CombatTerrainTuning.HAZARD;
        return CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE;
    }

    private static Set<String> names(String words) {
        Set<String> result = new HashSet<>();
        for (String name : words.split(CombatTerrainDiagnostics.SINGLE_SPACE))
            result.add(CombatTerrainReflectionSpec.TARGET_CLASS + name);
        return result;
    }

    private static Class<?> owner(Class<?> type, String named, String mapped, Class<?>... parameters) {
        for (String name : Arrays.asList(named, mapped)) {
            try {
                Method method = type.getMethod(name, parameters);
                return method.getDeclaringClass();
            } catch (ReflectiveOperationException ignored) {}
        }
        return null;
    }

    private static BlockShapes failed(Result result) {
        return new BlockShapes(
            result,
            CombatTerrainTuning.UNKNOWN,
            new double[0],
            CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE);
    }

    private static int floor(double value) {
        return MathHelper.floor_double(value);
    }

    private static long volume(int x, int y, int z, int ex, int ey, int ez) {
        return ex < x || ey < y || ez < z ? CombatTerrainTuning.UNSET_OR_NOT_FOUND
            : (long) (ex - x + 1) * (ey - y + 1) * (ez - z + 1);
    }

    private static boolean finite(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    private static boolean valid(AxisAlignedBB box) {
        return box != null && finite(box.minX, box.minY, box.minZ)
            && finite(box.maxX, box.maxY, box.maxZ)
            && box.minX < box.maxX
            && box.minY < box.maxY
            && box.minZ < box.maxZ;
    }

    private static AxisAlignedBB cellMask(int x, int y, int z) {
        return AxisAlignedBB.getBoundingBox(
            x - CombatTerrainTuning.COMBAT_SPACE_GET_BOUNDING_BOX_FIRST_FACTOR,
            y - CombatTerrainTuning.COMBAT_SPACE_GET_BOUNDING_BOX_FIRST_FACTOR,
            z - CombatTerrainTuning.COMBAT_SPACE_GET_BOUNDING_BOX_FIRST_FACTOR,
            x + CombatTerrainTuning.COMBAT_SPACE_GET_BOUNDING_BOX_FOURTH_FACTOR,
            y + CombatTerrainTuning.COMBAT_SPACE_GET_BOUNDING_BOX_FIFTH_FACTOR,
            z + CombatTerrainTuning.COMBAT_SPACE_GET_BOUNDING_BOX_FOURTH_FACTOR);
    }

    private static final class ShapeList extends AbstractList<AxisAlignedBB> {

        private final double[] values = new double[CombatTerrainTuning.MAX_SHAPES_PER_BLOCK
            * CombatTerrainTuning.COMBAT_SNAPSHOT_DOMAIN_PARAM];
        private final CombatWorkQuota quota;
        private int count, submitted;

        ShapeList(CombatWorkQuota quota) {
            this.quota = quota;
        }

        @Override
        public int size() {
            return count;
        }

        @Override
        public AxisAlignedBB get(int index) {
            if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
            int offset = index * CombatTerrainTuning.COMBAT_SNAPSHOT_DOMAIN_PARAM;
            return AxisAlignedBB.getBoundingBox(
                values[offset],
                values[offset + 1],
                values[offset + CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM],
                values[offset + CombatTerrainTuning.COMBAT_MOVEMENT_DOMAIN_PARAM],
                values[offset + CombatTerrainTuning.COMBAT_SPACE_DOMAIN_PARAM_2],
                values[offset + CombatTerrainTuning.COMBAT_SPACE_DOMAIN_PARAM]);
        }

        @Override
        public boolean add(AxisAlignedBB box) {
            if (submitted++ >= CombatTerrainTuning.MAX_SHAPES_PER_BLOCK) throw new Limit(Result.UNKNOWN);
            if (!quota.spend(Work.SHAPE)) throw new Limit(Result.BUDGET);
            if (box == null || !finite(box.minX, box.minY, box.minZ)
                || !finite(box.maxX, box.maxY, box.maxZ)
                || box.minX > box.maxX
                || box.minY > box.maxY
                || box.minZ > box.maxZ) throw new Limit(Result.UNKNOWN);
            if (box.minX == box.maxX || box.minY == box.maxY || box.minZ == box.maxZ) return true;
            int offset = count++ * CombatTerrainTuning.COMBAT_SNAPSHOT_DOMAIN_PARAM;
            values[offset] = box.minX;
            values[offset + 1] = box.minY;
            values[offset + CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM] = box.minZ;
            values[offset + CombatTerrainTuning.COMBAT_MOVEMENT_DOMAIN_PARAM] = box.maxX;
            values[offset + CombatTerrainTuning.COMBAT_SPACE_DOMAIN_PARAM_2] = box.maxY;
            values[offset + CombatTerrainTuning.COMBAT_SPACE_DOMAIN_PARAM] = box.maxZ;
            return true;
        }

        BlockShapes finish(int flags) {
            return finish(flags, null);
        }

        BlockShapes finish(int flags, DoorCell doorCell) {
            if (count != CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP
                && (flags & CombatTerrainTuning.RAY_BLOCKING) != 0)
                flags = flags & ~CombatTerrainTuning.RAY_BLOCKING | CombatTerrainTuning.RAY_UNKNOWN;
            return new BlockShapes(
                Result.SAFE,
                flags,
                Arrays.copyOf(values, count * CombatTerrainTuning.COMBAT_SNAPSHOT_DOMAIN_PARAM),
                count,
                doorCell);
        }
    }

    private static final class Limit extends RuntimeException {

        final Result result;

        Limit(Result result) {
            super(null, null, false, false);
            this.result = result;
        }
    }
}
