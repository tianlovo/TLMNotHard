package com.tianluoqaq.tlmnh.compat.etfuturum.common.physics;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import net.minecraft.util.Vec3;

import com.tianluoqaq.tlmnh.common.combat.spi.ProjectilePhysicsProvider;
import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.compat.etfuturum.EtFuturumCompat;

import cpw.mods.fml.common.Loader;

/** 实装EFR adjustedLiquidPhysics对非物品实体的水流步骤；不改世界或调用实体更新 */
public final class NativeLiquidPhysics {



    private NativeLiquidPhysics() {}

    public static Sample sample() {
        return new Sample(read());
    }

    public static final class Sample implements ProjectilePhysicsProvider.LiquidFlowSample {

        private final boolean adjusted;

        private Sample(boolean adjusted) {
            this.adjusted = adjusted;
        }

        @Override
        public boolean isCurrent() {
            return adjusted == read();
        }

        @Override
        public boolean adjusted() {
            return adjusted;
        }

        /** 对应liquidphysics.MixinBlockLiquid：在求和前按此格metadata缩放水平分量 */
        @Override
        public Vec3 cellFlow(Vec3 original, int metadata) {
            if (!adjusted) return original;
            double power = metadata == EtFuturumPhysicsTuning.TARGET_METADATA_THRESHOLD ? EtFuturumPhysicsTuning.LIQUID_PHYSICS_CELL_FLOW_UNIT_STEP : EtFuturumPhysicsTuning.LIQUID_PHYSICS_CELL_FLOW_UNIT_STEP - (metadata / EtFuturumPhysicsTuning.LIQUID_PHYSICS_CELL_FLOW_FACTOR) * (EtFuturumPhysicsTuning.LIQUID_PHYSICS_CELL_FLOW_UNIT_STEP - EtFuturumPhysicsTuning.LIQUID_PHYSICS_CELL_FLOW_FACTOR_2);
            return Vec3.createVectorHelper(original.xCoord * power, original.yCoord, original.zCoord * power);
        }

        /** 对应liquidphysics.MixinWorld：非EntityItem保留合流长度，上限1.15，Y仍为归一分量 */
        @Override
        public Vec3 normalizeTotal(Vec3 sum) {
            var normalized = sum.normalize();
            if (!adjusted) return normalized;
            double length = Math.min(sum.lengthVector(), EtFuturumPhysicsTuning.LIQUID_PHYSICS_MIN_SECOND_FACTOR);
            return Vec3.createVectorHelper(normalized.xCoord * length, normalized.yCoord, normalized.zCoord * length);
        }
    }

    private static boolean read() {
        try {
            return Api.CONFIG != null && Api.CONFIG.getBoolean(null);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(EtFuturumPhysicsDiagnostics.MSG_EFR_LIQUID_PHYSICS_CONFIG_UNAVAILABLE, failure);
        }
    }

    private static final class Api {

        static final Field CONFIG = resolve();

        private static Field resolve() {
            if (!Loader.isModLoaded(EtFuturumCompat.MOD_ID)) return null;
            String fingerprint = DependencyFingerprint.mod(EtFuturumCompat.MOD_ID);
            if (!fingerprint.endsWith(EtFuturumPhysicsIds.REVIEWED_ARTIFACT_REVIEWED + EtFuturumPhysicsIds.SOURCE_SHA256))
                throw new IllegalStateException(EtFuturumPhysicsDiagnostics.MSG_UNVERIFIED_EFR_LIQUID_PHYSICS_PROVIDER + fingerprint);
            try {
                var type = Class.forName(
                    EtFuturumPhysicsReflectionSpec.TARGET_CLASS_CONFIG_MIXINS,
                    false,
                    NativeLiquidPhysics.class.getClassLoader());
                var field = type.getField(EtFuturumPhysicsIds.ID_ADJUSTEDLIQUIDPHYSICS);
                if (field.getType() != boolean.class || !Modifier.isStatic(field.getModifiers()))
                    throw new IllegalStateException(EtFuturumPhysicsDiagnostics.MSG_INSTALLED_EFR_LIQUID_PHYSICS_CONFIG);
                return field;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(EtFuturumPhysicsDiagnostics.MSG_INSTALLED_EFR_LIQUID_PHYSICS_CONFIG_2, failure);
            }
        }
    }
}
