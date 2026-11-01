package com.tianluoqaq.tlmnh.compat.galacticraftcore.common.physics;

import java.lang.reflect.Method;

import net.minecraft.entity.projectile.EntityArrow;

import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

/** 实装Galacticraft的只读箭重力查询；没有该模组时使用原生EntityArrow的float常量 */
public final class NativeArrowGravity {

    private static final Method GALACTICRAFT = IntegrationGuard
        .call(ExternalFeature.ARROW_GRAVITY, NativeArrowGravity::resolve, () -> null);

    private NativeArrowGravity() {}

    public static boolean available() {
        return IntegrationGuard.available(ExternalFeature.ARROW_GRAVITY);
    }

    public static float forArrow(EntityArrow arrow) {
        return IntegrationGuard.call(ExternalFeature.ARROW_GRAVITY, () -> read(arrow), () -> 0F);
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
            return Class
                .forName(
                    "micdoodle8.mods.galacticraft.core.util.WorldUtil",
                    false,
                    NativeArrowGravity.class.getClassLoader())
                .getMethod("getArrowGravity", EntityArrow.class);
        } catch (ClassNotFoundException absent) {
            if (cpw.mods.fml.common.Loader.isModLoaded("GalacticraftCore"))
                throw new IllegalStateException("Installed arrow gravity class missing", absent);
            return null;
        } catch (NoSuchMethodException incompatible) {
            throw new IllegalStateException("Installed arrow gravity API differs", incompatible);
        }
    }
}
