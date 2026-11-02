package com.tianluoqaq.tlmnh.compat.galacticraftcore.common.physics;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.UUID;

import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;

import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

/** 实装Galacticraft的只读箭重力查询；没有该模组时使用原生EntityArrow的float常量 */
public final class NativeArrowGravity {

    private static final Method GALACTICRAFT = IntegrationGuard
        .call(ExternalFeature.ARROW_GRAVITY, NativeArrowGravity::resolve, () -> null);
    private static final ThreadLocal<Boolean> LAUNCH_QUERY = new ThreadLocal<>();

    private NativeArrowGravity() {}

    public static boolean available() {
        return IntegrationGuard.available(ExternalFeature.ARROW_GRAVITY);
    }

    public static float forArrow(EntityArrow arrow) {
        return IntegrationGuard.call(ExternalFeature.ARROW_GRAVITY, () -> read(arrow), () -> 0F);
    }

    /** 发射前只查询一次；查询回调必须保持实际箭和环境不变，失败没有可用物理输入 */
    public static LaunchSample sampleForLaunch(EntityArrow arrow) {
        return IntegrationGuard.call(ExternalFeature.ARROW_GRAVITY, () -> {
            if (Boolean.TRUE.equals(LAUNCH_QUERY.get()))
                throw new IllegalStateException("Reentrant arrow launch gravity query");
            LAUNCH_QUERY.set(Boolean.TRUE);
            try {
                return new LaunchSample(arrow);
            } finally {
                LAUNCH_QUERY.remove();
            }
        }, () -> null);
    }

    public static final class LaunchSample {

        public final float gravity;
        private final EntityArrow arrow;
        private final World world;
        private final WorldProvider provider;
        private final int dimension, entityId;
        private final UUID identity;

        private LaunchSample(EntityArrow arrow) {
            if (arrow == null || arrow.worldObj == null || arrow.worldObj.isRemote || arrow.worldObj.provider == null)
                throw new IllegalArgumentException("Actual server arrow required for launch gravity");
            this.arrow = arrow;
            world = arrow.worldObj;
            provider = world.provider;
            dimension = arrow.dimension;
            entityId = arrow.getEntityId();
            identity = arrow.getUniqueID();
            if (!isCurrent(arrow)) throw new IllegalStateException("Arrow launch identity or provider is inconsistent");
            var shooter = arrow.shootingEntity;
            var raw = snapshot(arrow);
            var bounds = bounds(arrow);
            boolean critical = arrow.getIsCritical();
            gravity = read(arrow);
            if (!isCurrent(arrow) || arrow.shootingEntity != shooter
                || arrow.getIsCritical() != critical
                || !raw.equals(snapshot(arrow))
                || !Arrays.equals(bounds, bounds(arrow)))
                throw new IllegalStateException("Arrow gravity query changed the actual arrow or its environment");
        }

        /** 定向与附魔可按发射合同更新箭，世界/provider/身份不允许被回调偷换 */
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
        if (GALACTICRAFT == null) return .05F;
        try {
            float result = ((Float) GALACTICRAFT.invoke(null, arrow)).floatValue();
            if (!Float.isFinite(result) || result < 0)
                throw new IllegalStateException("Invalid external arrow gravity");
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Installed arrow gravity query failed", failure);
        }
    }

    private static Method resolve() {
        try {
            var method = Class
                .forName(
                    "micdoodle8.mods.galacticraft.core.util.WorldUtil",
                    false,
                    NativeArrowGravity.class.getClassLoader())
                .getMethod("getArrowGravity", EntityArrow.class);
            if (method.getReturnType() != float.class || !Modifier.isStatic(method.getModifiers()))
                throw new IllegalStateException("Installed arrow gravity signature differs from the reviewed API");
            return method;
        } catch (ClassNotFoundException absent) {
            if (cpw.mods.fml.common.Loader.isModLoaded("GalacticraftCore"))
                throw new IllegalStateException("Installed arrow gravity class missing", absent);
            return null;
        } catch (NoSuchMethodException incompatible) {
            throw new IllegalStateException("Installed arrow gravity API differs", incompatible);
        }
    }
}
