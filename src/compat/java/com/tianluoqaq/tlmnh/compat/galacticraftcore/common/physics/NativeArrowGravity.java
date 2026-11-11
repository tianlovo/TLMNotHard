package com.tianluoqaq.tlmnh.compat.galacticraftcore.common.physics;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.UUID;

import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;

import com.tianluoqaq.tlmnh.common.combat.spi.ProjectilePhysicsProvider;
import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;

/** 读取星系（Galacticraft）的维度箭矢重力；未安装该模组时沿用原版 EntityArrow 的默认重力 0.05F */
public final class NativeArrowGravity {

    private static final MethodHandle GALACTICRAFT = IntegrationGuard
        .call(ExternalFeature.ARROW_GRAVITY, NativeArrowGravity::resolve, () -> null);
    private static final ThreadLocal<Boolean> LAUNCH_QUERY = new ThreadLocal<>();

    private NativeArrowGravity() {}

    public static boolean available() {
        return IntegrationGuard.available(ExternalFeature.ARROW_GRAVITY);
    }

    public static float forArrow(EntityArrow arrow) {
        return IntegrationGuard.call(ExternalFeature.ARROW_GRAVITY, () -> read(arrow), () -> 0F);
    }

    /** 发射前采样一次当前箭矢重力，同时校验外部查询回调没有篡改箭实体或所在世界 */
    public static LaunchSample sampleForLaunch(EntityArrow arrow) {
        return IntegrationGuard.call(ExternalFeature.ARROW_GRAVITY, () -> {
            if (Boolean.TRUE.equals(LAUNCH_QUERY.get())) throw new IllegalStateException(
                GalacticraftPhysicsDiagnostics.MSG_REENTRANT_ARROW_LAUNCH_GRAVITY_QUERY);
            LAUNCH_QUERY.set(Boolean.TRUE);
            try {
                return new LaunchSample(arrow);
            } finally {
                LAUNCH_QUERY.remove();
            }
        }, () -> null);
    }

    public static final class LaunchSample implements ProjectilePhysicsProvider.ArrowLaunchSample {

        public final float gravity;
        private final EntityArrow arrow;
        private final World world;
        private final WorldProvider provider;
        private final int dimension, entityId;
        private final UUID identity;

        private LaunchSample(EntityArrow arrow) {
            if (arrow == null || arrow.worldObj == null || arrow.worldObj.isRemote || arrow.worldObj.provider == null)
                throw new IllegalArgumentException(GalacticraftPhysicsDiagnostics.MSG_ACTUAL_SERVER_ARROW_REQUIRED_FOR);
            this.arrow = arrow;
            world = arrow.worldObj;
            provider = world.provider;
            dimension = arrow.dimension;
            entityId = arrow.getEntityId();
            identity = arrow.getUniqueID();
            if (!isCurrent(arrow))
                throw new IllegalStateException(GalacticraftPhysicsDiagnostics.MSG_ARROW_LAUNCH_IDENTITY_OR_PROVIDER);
            var shooter = arrow.shootingEntity;
            var raw = snapshot(arrow);
            var bounds = bounds(arrow);
            boolean critical = arrow.getIsCritical();
            gravity = read(arrow);
            if (!isCurrent(arrow) || arrow.shootingEntity != shooter
                || arrow.getIsCritical() != critical
                || !raw.equals(snapshot(arrow))
                || !Arrays.equals(bounds, bounds(arrow)))
                throw new IllegalStateException(GalacticraftPhysicsDiagnostics.MSG_ARROW_GRAVITY_QUERY_CHANGED_THE);
        }

        /** 发射流程可正常写入朝向与附魔，但箭矢所属世界、维度提供方及实体身份须保持一致 */
        @Override
        public float gravity() {
            return gravity;
        }

        @Override
        public boolean isCurrent(EntityArrow candidate) {
            return candidate == arrow && available()
                && !arrow.isDead
                && arrow.worldObj == world
                && world.provider == provider
                && provider.dimensionId == dimension
                && arrow.dimension == dimension
                && arrow.getEntityId() == entityId
                && identity.equals(arrow.getUniqueID());
        }

        /** 星系的箭矢重力仅取决于世界与维度提供方，同帧同维度的原版箭可复用本次采样 */
        @Override
        public boolean sameObservationInput(EntityArrow candidate) {
            return arrow.getClass() == EntityArrow.class && candidate != null
                && candidate.getClass() == EntityArrow.class
                && isCurrent(arrow)
                && !candidate.isDead
                && candidate.worldObj == world
                && candidate.worldObj.provider == provider
                && candidate.dimension == dimension
                && provider.dimensionId == dimension;
        }

        private static NBTTagCompound snapshot(EntityArrow arrow) {
            var raw = new NBTTagCompound();
            arrow.writeToNBT(raw);
            return (NBTTagCompound) raw.copy();
        }

        private static double[] bounds(EntityArrow arrow) {
            return new double[] { arrow.boundingBox.minX, arrow.boundingBox.minY, arrow.boundingBox.minZ,
                arrow.boundingBox.maxX, arrow.boundingBox.maxY, arrow.boundingBox.maxZ };
        }
    }

    private static float read(EntityArrow arrow) {
        if (GALACTICRAFT == null) return GalacticraftPhysicsTuning.VANILLA_ARROW_GRAVITY;
        try {
            float result = (float) GALACTICRAFT.invokeExact(arrow);
            if (!Float.isFinite(result) || result < 0)
                throw new IllegalStateException(GalacticraftPhysicsDiagnostics.MSG_INVALID_EXTERNAL_ARROW_GRAVITY);
            return result;
        } catch (RuntimeException | Error direct) {
            throw direct;
        } catch (Throwable failure) {
            throw new IllegalStateException(
                GalacticraftPhysicsDiagnostics.MSG_INSTALLED_ARROW_GRAVITY_QUERY_FAILED,
                failure);
        }
    }

    private static MethodHandle resolve() {
        return OptionalApi.findStatic(
            KnownModIds.GALACTICRAFT_CORE,
            GalacticraftPhysicsReflectionSpec.TARGET_CLASS_WORLD_UTIL,
            GalacticraftPhysicsIds.ID_GETARROWGRAVITY,
            MethodType.methodType(float.class, EntityArrow.class));
    }
}
