package com.tianluoqaq.tlmnh.common.ai.enhanced.combat;

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
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.DoorCell;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Geometry;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatWorkQuota.Work;
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

    public static final int MAX_BLOCKS = 128, MAX_SHAPES_PER_BLOCK = 16;
    private static final double EPSILON = .0001;
    private static final Set<String> FULL = names(
        "Block BlockStone BlockGrass BlockDirt BlockGravel BlockSand "
            + "BlockSandStone BlockClay BlockNetherrack BlockOre BlockWood BlockNewLog BlockOldLog BlockCompressed "
            + "BlockCompressedPowered BlockStoneBrick BlockObsidian BlockBreakable BlockGlass BlockStainedGlass "
            + "BlockHardenedClay BlockColored BlockBookshelf BlockSponge BlockHay BlockMelon BlockPumpkin BlockIce "
            + "BlockPackedIce BlockSnowBlock BlockFurnace BlockDispenser BlockDropper BlockWorkbench BlockJukebox "
            + "BlockNote BlockTNT BlockRedstoneLight BlockCommandBlock BlockHugeMushroom BlockFarmland");
    private static final Set<String> LOCAL = names(
        "BlockStoneSlab BlockWoodSlab BlockDoor BlockTrapDoor " + "BlockFenceGate BlockSnow");
    private static final Set<String> NEIGHBOR = names(
        "BlockPane BlockStainedGlassPane BlockStairs BlockFence BlockWall");
    private static final Set<String> RAY_CUBES = names(
        "BlockStone BlockDirt BlockGlass BlockStainedGlass BlockSandStone BlockNetherrack BlockObsidian BlockStoneBrick");
    private static final ClassValue<Class<?>> MAID_COLLISION = new ClassValue<Class<?>>() {

        @Override
        protected Class<?> computeValue(Class<?> type) {
            Class<?> declared = owner(type, "getCollisionBox", "func_70114_g", Entity.class);
            return declared == null ? Void.class : declared;
        }
    };
    private static final ClassValue<EntityCollision> ENTITY_COLLISION = new ClassValue<EntityCollision>() {

        @Override
        protected EntityCollision computeValue(Class<?> type) {
            Class<?> box = owner(type, "getBoundingBox", "func_70046_E");
            Class<?> parts = owner(type, "getParts", "func_70021_al");
            int kind = parts != Entity.class ? -1
                : box == Entity.class ? 0
                    : box != null && box.getName()
                        .equals("net.minecraft.entity.item.EntityBoat") ? 1 : -1;
            return new EntityCollision(kind, box, parts);
        }
    };

    private CombatSpace() {}

    /** 只记录当前调用已经取得的结果，不补查世界，也不参与执行许可 */
    public static final class Diagnostic {

        public Result result;
        public String reason = "not_checked", maidOwner = "unqueried", blockType = "unqueried", entityType = "none",
            entityBoxOwner = "unqueried", entityPartsOwner = "unqueried";
        public long tick;
        public boolean block;
        public int x, y, z;
        public double radius = Double.NaN;

        private void reset(long at) {
            tick = at;
            result = null;
            reason = "not_checked";
            maidOwner = blockType = entityBoxOwner = entityPartsOwner = "unqueried";
            entityType = "none";
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
            return result + "/"
                + reason
                + "@"
                + tick
                + ":maid="
                + maidOwner
                + ":radius="
                + radius
                + (block ? ":block=" + x + "," + y + "," + z + "/" + blockType : "")
                + ":entity="
                + entityType
                + ":boxOwner="
                + entityBoxOwner
                + ":partsOwner="
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
        return type == null || type == Void.class ? "unresolved" : type.getName();
    }

    /** x/y/z是实际脚底中心，不是方块索引 */
    public static Result safeStandingPoint(EntityMaid maid, double x, double y, double z, CombatWorkQuota quota) {
        return safeStandingPoint(maid, x, y, z, quota, null);
    }

    public static Result safeStandingPoint(EntityMaid maid, double x, double y, double z, CombatWorkQuota quota,
        Diagnostic diagnostic) {
        if (diagnostic != null) diagnostic.reset(quota.tick);
        if (!finite(x, y, z)) return recorded(diagnostic, Result.UNKNOWN, "invalid_point");
        double half = maid.width * .5;
        AxisAlignedBB body = AxisAlignedBB.getBoundingBox(
            x - half + EPSILON,
            y + .02,
            z - half + EPSILON,
            x + half - EPSILON,
            y + maid.height - .02,
            z + half - EPSILON);
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
        double half = width * .5;
        // 包含safeStandingPoint原先检查的同一body；更大的sweep仍遵守boundedClear原体积上限
        if (!valid(sweep) || !finite(x, y, z)
            || sweep.minX > x - half + EPSILON
            || sweep.maxX < x + half - EPSILON
            || sweep.minY > y + .02
            || sweep.maxY < y + height - .02
            || sweep.minZ > z - half + EPSILON
            || sweep.maxZ < z + half - EPSILON) return recorded(head, Result.UNKNOWN, "jump_sweep_does_not_cover_body");
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
            || current.maxZ != maxZ) return recorded(support, Result.UNKNOWN, "jump_body_changed_after_sweep");
        return standingSupport(maid, x, y, z, quota, support);
    }

    private static Result standingSupport(EntityMaid maid, double x, double y, double z, CombatWorkQuota quota,
        Diagnostic diagnostic) {
        double half = maid.width * .5;
        int minX = floor(x - half), maxX = floor(x + half), minZ = floor(z - half), maxZ = floor(z + half);
        int top = floor(y - EPSILON);
        BlockReader reader = reader(maid, minX, top - 1, minZ, maxX, top, maxZ, quota);
        if (reader.result != Result.SAFE) return recorded(diagnostic, reader.result, "support_reader");
        for (int dx = -1; dx <= 1; dx += 2) for (int dz = -1; dz <= 1; dz += 2) {
            double px = x + dx * half * .8, pz = z + dz * half * .8;
            boolean supported = false;
            for (int py = top; py >= top - 1; py--) {
                BlockShapes shapes = reader.block(floor(px), py, floor(pz), cellMask(floor(px), py, floor(pz)));
                if (shapes.result != Result.SAFE) {
                    if (diagnostic != null) diagnostic.atBlock(floor(px), py, floor(pz), reader);
                    return recorded(diagnostic, shapes.result, "support_query");
                }
                if ((shapes.flags & (Geometry.HAZARD | Geometry.WATER)) != 0) {
                    if (diagnostic != null) diagnostic.atBlock(floor(px), py, floor(pz), reader);
                    return recorded(diagnostic, Result.BLOCKED, "support_hazard");
                }
                for (int i = 0; i < shapes.count; i++) {
                    if (!quota.spend(Work.COLLISION))
                        return recorded(diagnostic, Result.BUDGET, "support_collision_budget");
                    int offset = i * 6;
                    if (px >= shapes.boxes[offset] - EPSILON && px <= shapes.boxes[offset + 3] + EPSILON
                        && pz >= shapes.boxes[offset + 2] - EPSILON
                        && pz <= shapes.boxes[offset + 5] + EPSILON
                        && shapes.boxes[offset + 4] >= y - .12
                        && shapes.boxes[offset + 4] <= y + .04) {
                        supported = true;
                        break;
                    }
                }
                if (supported) break;
            }
            if (!supported) {
                if (diagnostic != null) diagnostic.atBlock(floor(px), top - 1, floor(pz), reader);
                return recorded(diagnostic, Result.BLOCKED, "missing_support");
            }
        }
        return recorded(diagnostic, Result.SAFE, "supported");
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
        if (collisionOwner != Entity.class) return recorded(diagnostic, Result.UNKNOWN, "maid_collision_owner");
        if (!valid(box)) return recorded(diagnostic, Result.UNKNOWN, "invalid_box");
        if (!quota.spend(Work.FINAL_CHECK)) return recorded(diagnostic, Result.BUDGET, "final_check_budget");
        int minX = floor(box.minX), maxX = floor(box.maxX - EPSILON);
        int minY = floor(box.minY) - 1, maxY = floor(box.maxY - EPSILON);
        int minZ = floor(box.minZ), maxZ = floor(box.maxZ - EPSILON);
        long volume = volume(minX, minY, minZ, maxX, maxY, maxZ);
        if (volume < 0 || volume > MAX_BLOCKS || maxX - minX > 7 || maxY - minY > 7 || maxZ - minZ > 7)
            return recorded(diagnostic, Result.UNKNOWN, "block_bounds_limit");
        BlockReader reader = reader(maid, minX, minY, minZ, maxX, maxY, maxZ, quota);
        if (reader.result != Result.SAFE) return recorded(diagnostic, reader.result, "clear_reader");
        for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
            BlockShapes shapes = reader.block(x, y, z, box);
            if (shapes.result != Result.SAFE) {
                if (diagnostic != null) diagnostic.atBlock(x, y, z, reader);
                return recorded(diagnostic, shapes.result, "clear_block_query");
            }
            if ((shapes.flags & (Geometry.HAZARD | Geometry.WATER)) != 0
                && box.intersectsWith(AxisAlignedBB.getBoundingBox(x, y, z, x + 1, y + 1, z + 1))) {
                if (diagnostic != null) diagnostic.atBlock(x, y, z, reader);
                return recorded(diagnostic, Result.BLOCKED, "clear_hazard");
            }
            if (shapes.count > 0) {
                if (diagnostic != null) diagnostic.atBlock(x, y, z, reader);
                return recorded(diagnostic, Result.BLOCKED, "clear_block_collision");
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
        private String lastBlockType = "unqueried";

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
            if (count < 0 || count > 512
                || minY < 0
                || maxY >= world.getHeight()
                || minX < -29999998
                || maxX > 29999997
                || minZ < -29999998
                || maxZ > 29999997) {
                result = Result.UNKNOWN;
                return;
            }
            result = loaded(world, minX - 1, minZ - 1, maxX + 1, maxZ + 1, quota);
        }

        public BlockShapes block(int x, int y, int z, AxisAlignedBB mask) {
            lastBlockType = "unqueried";
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
                        Geometry.RAY_UNKNOWN | ((block == Blocks.wooden_door ? maid.getConfigManager()
                            .isOpenDoor()
                            : maid.getConfigManager()
                                .isOpenFenceGate()) ? Geometry.OPENABLE : 0),
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
            if (RAY_CUBES.contains(nativeName) || nativeName.equals("net.minecraft.block.BlockStoneSlab")
                || nativeName.equals("net.minecraft.block.BlockWoodSlab")
                || nativeName.equals("net.minecraft.block.BlockDoor")
                || nativeName.equals("net.minecraft.block.BlockTrapDoor")) flags |= Geometry.RAY_BLOCKING;
            else if (block != Blocks.air) flags |= Geometry.RAY_UNKNOWN;
            // 只标记已核shape提供方及自身配置，不新增方块/metadata/邻接查询
            if (block == Blocks.wooden_door && maid.getConfigManager()
                .isOpenDoor() || block == Blocks.fence_gate
                    && maid.getConfigManager()
                        .isOpenFenceGate())
                flags |= Geometry.OPENABLE;
            if (kind == 0) return new BlockShapes(Result.SAFE, flags, new double[0], 0);
            ShapeList shapes = new ShapeList(quota);
            if (kind == 1 || block == Blocks.soul_sand) {
                double height = block == Blocks.soul_sand ? .875 : 1;
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
                int[][] offsets = { { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 } };
                for (int[] offset : offsets) {
                    if (!quota.spend(Work.BLOCK)) return failed(Result.BUDGET);
                    if (kind(world.getBlock(x + offset[0], y, z + offset[1])) < 0) return failed(Result.UNKNOWN);
                }
            }
            float a = (float) block.getBlockBoundsMinX(), b = (float) block.getBlockBoundsMinY(),
                c = (float) block.getBlockBoundsMinZ(), d = (float) block.getBlockBoundsMaxX(),
                e = (float) block.getBlockBoundsMaxY(), f = (float) block.getBlockBoundsMaxZ();
            try {
                // 清掉共享遗留bounds；多形状提供方会按当前状态逐片提交
                block.setBlockBounds(0, 0, 0, 1, 1, 1);
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
                int offset = i * 6;
                ax = Math.min(ax, boxes[offset]);
                ay = Math.min(ay, boxes[offset + 1]);
                az = Math.min(az, boxes[offset + 2]);
                bx = Math.max(bx, boxes[offset + 3]);
                by = Math.max(by, boxes[offset + 4]);
                bz = Math.max(bz, boxes[offset + 5]);
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
        if (!Double.isFinite(radius) || radius < 0 || radius > 4)
            return recorded(diagnostic, Result.UNKNOWN, "entity_radius_bound");
        AxisAlignedBB broad = box.expand(.25, .25, .25);
        int minX = floor((broad.minX - radius) / 16), maxX = floor((broad.maxX + radius) / 16);
        int minZ = floor((broad.minZ - radius) / 16), maxZ = floor((broad.maxZ + radius) / 16);
        if ((maxX - minX + 1) * (maxZ - minZ + 1) > 16)
            return recorded(diagnostic, Result.UNKNOWN, "entity_chunk_limit");
        for (int cx = minX; cx <= maxX; cx++) for (int cz = minZ; cz <= maxZ; cz++) {
            if (!quota.spend(Work.CHUNK)) return recorded(diagnostic, Result.BUDGET, "entity_chunk_budget");
            if (!maid.worldObj.getChunkProvider()
                .chunkExists(cx, cz)) return recorded(diagnostic, Result.UNKNOWN, "entity_chunk_unloaded");
            var chunk = maid.worldObj.getChunkFromChunkCoords(cx, cz);
            int low = Math.max(0, floor((broad.minY - radius) / 16));
            int high = Math.min(chunk.entityLists.length - 1, floor((broad.maxY + radius) / 16));
            for (int section = low; section <= high; section++) {
                List<?> entities = chunk.entityLists[section];
                for (int index = 0; index < entities.size(); index++) {
                    if (!quota.spend(Work.ENTITY))
                        return recorded(diagnostic, Result.BUDGET, "entity_partition_budget");
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
                    if (collision.kind < 0) return recorded(diagnostic, Result.UNKNOWN, "entity_collision_owner");
                    // Entity返回null；Boat只返回public boundingBox；不调用未知覆盖或Minecart动态handler
                    if (collision.kind > 0 && entity.boundingBox.intersectsWith(box))
                        return recorded(diagnostic, Result.BLOCKED, "entity_collision");
                }
            }
        }
        return recorded(diagnostic, Result.SAFE, "clear");
    }

    private static Result loaded(World world, int minX, int minZ, int maxX, int maxZ, CombatWorkQuota quota) {
        for (int x = minX >> 4; x <= maxX >> 4; x++) for (int z = minZ >> 4; z <= maxZ >> 4; z++) {
            if (!quota.spend(Work.CHUNK)) return Result.BUDGET;
            if (!world.getChunkProvider()
                .chunkExists(x, z)) return Result.UNKNOWN;
        }
        return Result.SAFE;
    }

    private static int kind(Block block) {
        String name = block.getClass()
            .getName();
        if (auditedGtCube(name)) return 1;
        Object registered = Block.blockRegistry.getNameForObject(block);
        if (!(registered instanceof String id) || !id.startsWith("minecraft:")
            || !name.startsWith("net.minecraft.block.")) return -1;
        if (empty(block)) return 0;
        if (FULL.contains(name)) return 1;
        if (LOCAL.contains(name) || NEIGHBOR.contains(name) || block == Blocks.soul_sand) return 2;
        return -1;
    }

    private static boolean auditedGtCube(String name) {
        if (!name.equals("gregtech.common.blocks.BlockConcretes") && !name.equals("gregtech.common.blocks.BlockStones"))
            return false;
        var mod = Loader.instance()
            .getIndexedModList()
            .get("gregtech");
        return mod != null && "5.09.54.265".equals(mod.getVersion());
    }

    private static int queryAllowance(Block block) {
        String name = block.getClass()
            .getName();
        if (name.endsWith("BlockPane") || name.endsWith("BlockStainedGlassPane") || name.endsWith("BlockStairs"))
            return 12;
        if (name.endsWith("BlockFence") || name.endsWith("BlockWall")) return 4;
        return name.endsWith("BlockDoor") ? 2 : 1;
    }

    private static boolean needsNeighborAdmission(Block block) {
        String name = block.getClass()
            .getName();
        return NEIGHBOR.contains(name) && !name.equals("net.minecraft.block.BlockStairs");
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
        if (block == Blocks.water || block == Blocks.flowing_water) return Geometry.WATER;
        if (block == Blocks.lava || block == Blocks.flowing_lava
            || block == Blocks.fire
            || block == Blocks.cactus
            || block == Blocks.web) return Geometry.HAZARD;
        return 0;
    }

    private static Set<String> names(String words) {
        Set<String> result = new HashSet<>();
        for (String name : words.split(" ")) result.add("net.minecraft.block." + name);
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
        return new BlockShapes(result, Geometry.UNKNOWN, new double[0], 0);
    }

    private static int floor(double value) {
        return MathHelper.floor_double(value);
    }

    private static long volume(int x, int y, int z, int ex, int ey, int ez) {
        return ex < x || ey < y || ez < z ? -1 : (long) (ex - x + 1) * (ey - y + 1) * (ez - z + 1);
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
        return AxisAlignedBB.getBoundingBox(x - .01, y - .01, z - .01, x + 1.01, y + 2.01, z + 1.01);
    }

    private static final class ShapeList extends AbstractList<AxisAlignedBB> {

        private final double[] values = new double[MAX_SHAPES_PER_BLOCK * 6];
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
            int offset = index * 6;
            return AxisAlignedBB.getBoundingBox(
                values[offset],
                values[offset + 1],
                values[offset + 2],
                values[offset + 3],
                values[offset + 4],
                values[offset + 5]);
        }

        @Override
        public boolean add(AxisAlignedBB box) {
            if (submitted++ >= MAX_SHAPES_PER_BLOCK) throw new Limit(Result.UNKNOWN);
            if (!quota.spend(Work.SHAPE)) throw new Limit(Result.BUDGET);
            if (box == null || !finite(box.minX, box.minY, box.minZ)
                || !finite(box.maxX, box.maxY, box.maxZ)
                || box.minX > box.maxX
                || box.minY > box.maxY
                || box.minZ > box.maxZ) throw new Limit(Result.UNKNOWN);
            if (box.minX == box.maxX || box.minY == box.maxY || box.minZ == box.maxZ) return true;
            int offset = count++ * 6;
            values[offset] = box.minX;
            values[offset + 1] = box.minY;
            values[offset + 2] = box.minZ;
            values[offset + 3] = box.maxX;
            values[offset + 4] = box.maxY;
            values[offset + 5] = box.maxZ;
            return true;
        }

        BlockShapes finish(int flags) {
            return finish(flags, null);
        }

        BlockShapes finish(int flags, DoorCell doorCell) {
            if (count != 1 && (flags & Geometry.RAY_BLOCKING) != 0)
                flags = flags & ~Geometry.RAY_BLOCKING | Geometry.RAY_UNKNOWN;
            return new BlockShapes(Result.SAFE, flags, Arrays.copyOf(values, count * 6), count, doorCell);
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
