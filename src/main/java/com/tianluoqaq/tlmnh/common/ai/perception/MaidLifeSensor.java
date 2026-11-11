package com.tianluoqaq.tlmnh.common.ai.perception;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidLifeCandidate;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidLifeCandidate.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.WorkComponent;
import com.tianluoqaq.tlmnh.common.life.bed.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.life.cabinet.TileEntitySnackCabinet;

/** 只读取附近已加载区块的TE表，执行许可仍由生活动作重新检查 */
public final class MaidLifeSensor {

    private final EntityMaid maid;
    private Object world;
    private MaidPolicyContext.Token token;
    private long observedAt;
    private int cursor;

    public MaidLifeSensor(EntityMaid maid) {
        this.maid = maid;
    }

    public List<MaidLifeCandidate> scan(MaidPolicyContext context, boolean rest) {
        if (!(maid.worldObj instanceof WorldServer server) || context == null || !context.isCurrent(context.getToken()))
            return Collections.emptyList();
        long now = server.getTotalWorldTime();
        if (world != server || token != context.getToken() || now < observedAt)
            cursor = MaidPerceptionTuning.DANGER_SENSOR_NEXT_SCAN;
        world = server;
        token = context.getToken();
        observedAt = now;
        var telemetry = maid.getBrainCoordinator()
            .getTelemetry();
        boolean recording = telemetry.isEnabled();
        long started = recording ? System.nanoTime() : MaidPerceptionTuning.DANGER_SENSOR_NEXT_SCAN;
        var home = maid.getHomeArea();
        if (home.enabled() && !home.sameDimension()) return Collections.emptyList();
        int radius = home.enabled()
            ? rest ? home.radius() : Math.min(MaidPerceptionTuning.LIFE_SENSOR_MIN_FIRST_PARAM, home.radius())
            : rest ? MaidPerceptionTuning.LIFE_SENSOR_SCAN_PARAM : MaidPerceptionTuning.LIFE_SENSOR_MIN_FIRST_PARAM;
        double centerX = home.enabled() ? home.center().x + MaidPerceptionTuning.CENTER_OFFSET_D : maid.posX;
        double centerZ = home.enabled() ? home.center().z + MaidPerceptionTuning.CENTER_OFFSET_D : maid.posZ;
        var tiles = new ArrayList<TileEntity>();
        for (int cx = MathHelper.floor_double(centerX - radius)
            >> MaidPerceptionTuning.DANGER_SENSOR_EXPAND_SECOND_PARAM,
            maxX = MathHelper.floor_double(centerX + radius)
                >> MaidPerceptionTuning.DANGER_SENSOR_EXPAND_SECOND_PARAM; cx <= maxX; cx++)
            for (int cz = MathHelper.floor_double(centerZ - radius)
                >> MaidPerceptionTuning.DANGER_SENSOR_EXPAND_SECOND_PARAM,
                maxZ = MathHelper.floor_double(centerZ + radius)
                    >> MaidPerceptionTuning.DANGER_SENSOR_EXPAND_SECOND_PARAM; cz <= maxZ; cz++) {
                        var chunk = loadedChunk(server, cx, cz);
                        if (chunk != null) tiles.addAll(chunk.chunkTileEntityMap.values());
                    }
        int size = tiles.size(), processed = Math.min(size, MaidPerceptionTuning.SCAN_BUDGET_2);
        var result = new ArrayList<MaidLifeCandidate>();
        for (int i = 0; i < processed; i++) {
            var tile = tiles.get(Math.floorMod(cursor++, size));
            if (tile == null || tile.isInvalid() || tile.getWorldObj() != server) continue;
            boolean correctType = rest ? tile instanceof TileEntityMaidBed : tile instanceof TileEntitySnackCabinet;
            if (!correctType || !withinSearch(rest, tile.xCoord, tile.yCoord, tile.zCoord)) continue;
            result.add(
                new MaidLifeCandidate(
                    rest ? Kind.BED : Kind.SNACK,
                    server.provider.dimensionId,
                    tile.xCoord,
                    tile.yCoord,
                    tile.zCoord,
                    now,
                    server,
                    context,
                    tile));
        }
        telemetry.scan(
            MaidPerceptionIds.ID_LIFE_TILES,
            size,
            processed,
            MaidPerceptionIds.ID_LOADED_NEARBY_CHUNK_TE_MAPS_PARTIAL + (size > processed)
                + MaidPerceptionIds.ID_REST
                + rest);
        if (recording)
            telemetry.work(MaidPerceptionIds.ID_LIFE_TILES, WorkComponent.SENSOR, System.nanoTime() - started);
        return Collections.unmodifiableList(result);
    }

    public TileEntity resolve(MaidLifeCandidate candidate, MaidPolicyContext context) {
        if (candidate == null || !(maid.worldObj instanceof WorldServer server)
            || candidate.getDimension() != server.provider.dimensionId
            || loadedChunk(
                server,
                candidate.getX() >> MaidPerceptionTuning.DANGER_SENSOR_EXPAND_SECOND_PARAM,
                candidate.getZ() >> MaidPerceptionTuning.DANGER_SENSOR_EXPAND_SECOND_PARAM) == null)
            return null;
        var tile = server.getTileEntity(candidate.getX(), candidate.getY(), candidate.getZ());
        if (tile == null || tile.isInvalid()
            || tile.getWorldObj() != server
            || tile.xCoord != candidate.getX()
            || tile.yCoord != candidate.getY()
            || tile.zCoord != candidate.getZ()
            || !candidate.matches(server, context, server.getTotalWorldTime(), tile)) return null;
        if (!withinSearch(candidate.getKind() == Kind.BED, candidate.getX(), candidate.getY(), candidate.getZ()))
            return null;
        return candidate.getKind() == Kind.BED ? tile instanceof TileEntityMaidBed ? tile : null
            : tile instanceof TileEntitySnackCabinet ? tile : null;
    }

    private static Chunk loadedChunk(WorldServer world, int x, int z) {
        var value = world.theChunkProviderServer.loadedChunkHashMap.getValueByKey(ChunkCoordIntPair.chunkXZ2Int(x, z));
        return value instanceof Chunk chunk ? chunk : null;
    }

    private boolean withinSearch(boolean rest, int x, int y, int z) {
        var home = maid.getHomeArea();
        int radius = home.enabled()
            ? rest ? home.radius() : Math.min(MaidPerceptionTuning.LIFE_SENSOR_MIN_FIRST_PARAM, home.radius())
            : rest ? MaidPerceptionTuning.LIFE_SENSOR_SCAN_PARAM : MaidPerceptionTuning.LIFE_SENSOR_MIN_FIRST_PARAM;
        double cx = maid.posX, cy = maid.posY, cz = maid.posZ;
        if (home.enabled()) {
            if (!home.contains(
                x,
                y + (rest ? MaidPerceptionTuning.DANGER_SENSOR_NEXT_SCAN : MaidPerceptionTuning.CONTAINS_SINGLE_UNIT),
                z)) return false;
            var center = home.center();
            cx = center.x + MaidPerceptionTuning.CENTER_OFFSET_D;
            cy = center.y;
            cz = center.z + MaidPerceptionTuning.CENTER_OFFSET_D;
        }
        double dx = x + MaidPerceptionTuning.CENTER_OFFSET_D - cx, dz = z + MaidPerceptionTuning.CENTER_OFFSET_D - cz;
        return Math.abs(y - cy)
            <= (home.enabled() && rest ? radius : MaidPerceptionTuning.LIFE_SENSOR_WITHIN_SEARCH_PARAM)
            && dx * dx + dz * dz <= radius * radius;
    }

    public void clear() {
        world = null;
        token = null;
        cursor = MaidPerceptionTuning.DANGER_SENSOR_NEXT_SCAN;
    }
}
