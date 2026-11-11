package com.tianluoqaq.tlmnh.common.lifecycle.world;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.minecraft.entity.EntityList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.MaidBrainLifecycle;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

/** 原生维度转移的女仆边界：目标实际登记成功之前，源实体仍持有完整存货 */
public final class MaidDimensionTravel {

    private final EntityMaid maid;
    private boolean transferring;

    public MaidDimensionTravel(EntityMaid maid) {
        this.maid = Objects.requireNonNull(maid, LifecycleWorldIds.ID_MAID);
    }

    public boolean travel(int requestedDimension) {
        if (maid.worldObj == null || maid.worldObj.isRemote
            || !maid.isEntityAlive()
            || transferring
            || maid.getCombatHitReceipt()
                .blocksTransfer())
            return false;
        String key = LifecycleWorldIds.ID_DIMENSION_TRAVEL + maid.getUniqueID()
            + LifecycleWorldIds.PATH_SLASH_SEPARATOR
            + requestedDimension;
        if (!IntegrationGuard.available(Side.SERVER, key)) return false;
        EntityMaid candidate = null;
        WorldServer destination = null;
        UUID identity = maid.getUniqueID();
        int candidateId = LifecycleWorldTuning.UNSET_OR_NOT_FOUND;
        boolean committed = false, retiringSource = false;
        transferring = true;
        try {
            if (!(maid.worldObj instanceof WorldServer source) || !present(maid, source)
                || MaidBrainLifecycle.isWorldUnloading(source)) return false;
            int origin = maid.dimension;
            var sourceProvider = source.provider;
            if (sourceProvider.dimensionId != origin || DimensionManager.getWorld(origin) != source)
                throw new IllegalStateException(LifecycleWorldDiagnostics.MSG_MAID_SOURCE_DIMENSION_IS_NOT);
            int target = origin == LifecycleWorldTuning.TARGET_ORIGIN_THRESHOLD
                && requestedDimension == LifecycleWorldTuning.TARGET_ORIGIN_THRESHOLD
                    ? LifecycleWorldTuning.DIMENSION_TRAVEL_TRAVEL_ZERO_BASE
                    : requestedDimension;
            if (target == origin) return false;
            if (!DimensionManager.isDimensionRegistered(target)) throw new IllegalStateException(
                LifecycleWorldDiagnostics.MSG_MAID_DESTINATION_DIMENSION_IS_NOT + target);
            destination = MinecraftServer.getServer()
                .worldServerForDimension(target);
            if (destination == null || destination.provider.dimensionId != target
                || MaidBrainLifecycle.isWorldUnloading(destination))
                throw new IllegalStateException(
                    LifecycleWorldDiagnostics.MSG_MAID_DESTINATION_WORLD_IS_UNAVAILABLE + target);
            var destinationProvider = destination.provider;
            if (hasIdentity(destination, identity, null))
                throw new IllegalStateException(LifecycleWorldDiagnostics.MSG_MAID_IDENTITY_ALREADY_EXISTS_IN);
            var original = snapshot(maid);
            int sourceId = maid.getEntityId();
            var vehicle = maid.ridingEntity;
            var passenger = maid.riddenByEntity;
            BooleanSupplier sourceCurrent = () -> sourceCurrent(source, sourceProvider, origin, identity, original)
                && maid.getEntityId() == sourceId
                && maid.ridingEntity == vehicle
                && maid.riddenByEntity == passenger;
            var created = EntityList.createEntityByName(EntityList.getEntityString(maid), destination);
            if (!(created instanceof EntityMaid fresh) || created.getClass() != maid.getClass())
                throw new IllegalStateException(LifecycleWorldDiagnostics.MSG_NATIVE_ENTITY_FACTORY_DID_NOT);
            candidate = fresh;
            candidateId = candidate.getEntityId();
            candidate.copyDataFrom(maid, true);
            candidate.dimension = target;
            candidate.setWorld(destination);
            place(candidate, origin, requestedDimension, sourceProvider, destination);
            var staged = snapshot(candidate);
            if (!sourceCurrent.getAsBoolean() || !destinationCurrent(destination, destinationProvider, target)
                || !candidateCurrent(candidate, destination, target, identity, candidateId))
                throw new IllegalStateException(LifecycleWorldDiagnostics.MSG_MAID_DIMENSION_CONTEXT_CHANGED_DURING);
            // 这里只进行一次目标登记；原生SCM的临时源区块登记与忽略spawn返回值的路径不用于女仆
            if (!destination.spawnEntityInWorld(candidate)) return false;
            if (!present(candidate, destination)
                || !candidateCurrent(candidate, destination, target, identity, candidateId)
                || !staged.equals(snapshot(candidate))
                || hasIdentity(destination, identity, candidate)
                || !sourceCurrent.getAsBoolean()
                || !destinationCurrent(destination, destinationProvider, target))
                throw new IllegalStateException(LifecycleWorldDiagnostics.MSG_MAID_DESTINATION_SPAWN_CALLBACK_REVOKED);
            retiringSource = true;
            source.removeEntity(maid);
            committed = maid.isDead;
            if (!committed)
                throw new IllegalStateException(LifecycleWorldDiagnostics.MSG_MAID_SOURCE_REMAINED_ACTIVE_AFTER);
            source.resetUpdateEntityTick();
            destination.resetUpdateEntityTick();
            return true;
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.throwIfFatal(failure);
            // setDead的原生finally可能已完成移除，之后的行为清理异常不能再撤销唯一存活的目标
            if (retiringSource && maid.isDead) committed = true;
            IntegrationGuard.report(Side.SERVER, key, failure, owner());
            return committed;
        } finally {
            try {
                if (!committed && candidate != null) discardOwned(candidate, destination, identity, candidateId);
            } catch (Exception | LinkageError | AssertionError cleanup) {
                IntegrationGuard.throwIfFatal(cleanup);
                IntegrationGuard.report(Side.SERVER, key + LifecycleWorldIds.ID_CLEANUP, cleanup, owner());
            } finally {
                transferring = false;
            }
        }
    }

    /**
     * 坐标与End分支来自Forge1614 Entity.travelToDimension及ServerConfigurationManager.transferEntityToWorld
     * 保留实际默认Teleporter的动态分派；已核Hodgepodge的findingSpawnPoint保护在异常时也恢复
     */
    private static void place(EntityMaid candidate, int origin, int requested, WorldProvider sourceProvider,
        WorldServer destination) {
        double factor = sourceProvider.getMovementFactor() / destination.provider.getMovementFactor();
        if (!Double.isFinite(factor) || factor <= 0) throw new IllegalStateException(
            LifecycleWorldDiagnostics.MSG_INVALID_NATIVE_DIMENSION_MOVEMENT_FACTOR + factor);
        double x = candidate.posX * factor, z = candidate.posZ * factor;
        double oldX = candidate.posX, oldY = candidate.posY, oldZ = candidate.posZ;
        if (!Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(oldY))
            throw new IllegalStateException(LifecycleWorldDiagnostics.MSG_NATIVE_DIMENSION_PLACEMENT_COORDINATES_ARE);
        float yaw = candidate.rotationYaw;
        if (candidate.dimension == LifecycleWorldTuning.TARGET_ORIGIN_THRESHOLD) {
            var entrance = origin == LifecycleWorldTuning.TARGET_ORIGIN_THRESHOLD ? destination.getSpawnPoint()
                : destination.getEntrancePortalLocation();
            if (entrance == null)
                throw new IllegalStateException(LifecycleWorldDiagnostics.MSG_NATIVE_END_ENTRANCE_IS_UNAVAILABLE);
            x = entrance.posX;
            z = entrance.posZ;
            candidate.setLocationAndAngles(
                x,
                entrance.posY,
                z,
                LifecycleWorldTuning.DIMENSION_TRAVEL_SET_LOCATION_AND_ANGLES_FOURTH_PARA,
                LifecycleWorldTuning.DIMENSION_TRAVEL_TRAVEL_ZERO_BASE);
        }
        if (origin != LifecycleWorldTuning.TARGET_ORIGIN_THRESHOLD) {
            x = MathHelper.clamp_int(
                (int) x,
                LifecycleWorldTuning.DIMENSION_TRAVEL_CLAMP_INT_SECOND_PARAM,
                LifecycleWorldTuning.DIMENSION_TRAVEL_CLAMP_INT_THIRD_PARAM);
            z = MathHelper.clamp_int(
                (int) z,
                LifecycleWorldTuning.DIMENSION_TRAVEL_CLAMP_INT_SECOND_PARAM,
                LifecycleWorldTuning.DIMENSION_TRAVEL_CLAMP_INT_THIRD_PARAM);
            candidate.setLocationAndAngles(x, candidate.posY, z, candidate.rotationYaw, candidate.rotationPitch);
            var teleporter = destination.getDefaultTeleporter();
            if (teleporter == null) throw new IllegalStateException(
                LifecycleWorldDiagnostics.MSG_NATIVE_DESTINATION_TELEPORTER_IS_UNAVAILABLE);
            boolean finding = destination.findingSpawnPoint;
            destination.findingSpawnPoint = true;
            try {
                teleporter.placeInPortal(candidate, oldX, oldY, oldZ, yaw);
            } finally {
                destination.findingSpawnPoint = finding;
            }
        }
        if (origin == LifecycleWorldTuning.TARGET_ORIGIN_THRESHOLD
            && requested == LifecycleWorldTuning.TARGET_ORIGIN_THRESHOLD) {
            var spawn = destination.getSpawnPoint();
            candidate.setLocationAndAngles(
                spawn.posX,
                destination.getTopSolidOrLiquidBlock(spawn.posX, spawn.posZ),
                spawn.posZ,
                candidate.rotationYaw,
                candidate.rotationPitch);
        }
    }

    private boolean sourceCurrent(WorldServer source, WorldProvider provider, int dimension, UUID identity,
        NBTTagCompound original) {
        return !maid.getCombatHitReceipt()
            .blocksTransfer() && present(maid, source)
            && maid.dimension == dimension
            && identity.equals(maid.getUniqueID())
            && destinationCurrent(source, provider, dimension)
            && original.equals(snapshot(maid));
    }

    private static boolean destinationCurrent(WorldServer destination, WorldProvider provider, int dimension) {
        return destination.provider == provider && provider.dimensionId == dimension
            && DimensionManager.getWorld(dimension) == destination
            && !MaidBrainLifecycle.isWorldUnloading(destination);
    }

    private static boolean candidateCurrent(EntityMaid candidate, WorldServer destination, int dimension, UUID identity,
        int entityId) {
        return candidate.isEntityAlive() && candidate.worldObj == destination
            && candidate.dimension == dimension
            && candidate.getEntityId() == entityId
            && identity.equals(candidate.getUniqueID());
    }

    private static boolean present(EntityMaid entity, WorldServer world) {
        if (!entity.isEntityAlive() || entity.worldObj != world
            || world.getEntityByID(entity.getEntityId()) != entity
            || !entity.addedToChunk
            || !world.getChunkProvider()
                .chunkExists(entity.chunkCoordX, entity.chunkCoordZ))
            return false;
        for (Object value : world.loadedEntityList) if (value == entity) return true;
        return false;
    }

    private static boolean hasIdentity(WorldServer world, UUID identity, EntityMaid except) {
        for (Object value : world.loadedEntityList) if (value != except && value instanceof EntityMaid other
            && !other.isDead
            && identity.equals(other.getUniqueID())) return true;
        return false;
    }

    private static NBTTagCompound snapshot(EntityMaid entity) {
        var tag = new NBTTagCompound();
        entity.writeToNBT(tag);
        return tag;
    }

    private static void discardOwned(EntityMaid candidate, WorldServer destination, UUID identity, int entityId) {
        if (candidate.worldObj != destination || candidate.getEntityId() != entityId
            || !identity.equals(candidate.getUniqueID()))
            throw new IllegalStateException(LifecycleWorldDiagnostics.MSG_MAID_TRANSFER_CANDIDATE_CHANGED_OWNERSHIP);
        var indexed = destination.getEntityByID(entityId);
        if (indexed != null && indexed != candidate)
            throw new IllegalStateException(LifecycleWorldDiagnostics.MSG_MAID_TRANSFER_CANDIDATE_ID_BELONGS);
        // removeEntity只撤销本候选，不按UUID查找或删除回调另建的实体
        destination.removeEntity(candidate);
    }

    private UUID owner() {
        try {
            String value = maid.func_152113_b();
            return value == null || value.isEmpty() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException failure) {
            return null;
        }
    }
}
