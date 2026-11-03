package com.tianluoqaq.tlmnh.compat.etfuturum.common.physics;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import net.minecraft.util.Vec3;

import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;

import cpw.mods.fml.common.Loader;

/** 实装EFR adjustedLiquidPhysics对非物品实体的水流步骤；不改世界或调用实体更新。 */
public final class NativeLiquidPhysics {

    private static final String SOURCE_SHA256 = "dc1e1b813cdf783f9fc94216bb46fe007877c0270d52ea71f29ce0e0c86067b5";

    private NativeLiquidPhysics() {}

    public static Sample sample() {
        return new Sample(read());
    }

    public static final class Sample {

        private final boolean adjusted;

        private Sample(boolean adjusted) {
            this.adjusted = adjusted;
        }

        public boolean isCurrent() {
            return adjusted == read();
        }

        public boolean adjusted() {
            return adjusted;
        }

        /** 对应liquidphysics.MixinBlockLiquid：在求和前按此格metadata缩放水平分量。 */
        public Vec3 cellFlow(Vec3 original, int metadata) {
            if (!adjusted) return original;
            double power = metadata == 8 ? 1 : 1 - (metadata / 7D) * (1 - .4D);
            return Vec3.createVectorHelper(original.xCoord * power, original.yCoord, original.zCoord * power);
        }

        /** 对应liquidphysics.MixinWorld：非EntityItem保留合流长度，上限1.15，Y仍为归一分量。 */
        public Vec3 normalizeTotal(Vec3 sum) {
            var normalized = sum.normalize();
            if (!adjusted) return normalized;
            double length = Math.min(sum.lengthVector(), 1.15);
            return Vec3.createVectorHelper(normalized.xCoord * length, normalized.yCoord, normalized.zCoord * length);
        }
    }

    private static boolean read() {
        try {
            return Api.CONFIG != null && Api.CONFIG.getBoolean(null);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("EFR liquid physics config unavailable", failure);
        }
    }

    private static final class Api {

        static final Field CONFIG = resolve();

        private static Field resolve() {
            if (!Loader.isModLoaded("etfuturum")) return null;
            String fingerprint = DependencyFingerprint.mod("etfuturum");
            if (!fingerprint.endsWith(":sha256:" + SOURCE_SHA256))
                throw new IllegalStateException("Unverified EFR liquid physics provider: " + fingerprint);
            try {
                var type = Class.forName(
                    "ganymedes01.etfuturum.configuration.configs.ConfigMixins",
                    false,
                    NativeLiquidPhysics.class.getClassLoader());
                var field = type.getField("adjustedLiquidPhysics");
                if (field.getType() != boolean.class || !Modifier.isStatic(field.getModifiers()))
                    throw new IllegalStateException("Installed EFR liquid physics config signature differs");
                return field;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Installed EFR liquid physics config unavailable", failure);
            }
        }
    }
}
