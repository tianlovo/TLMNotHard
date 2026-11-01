package com.tianluoqaq.tlmnh.common.combat;

import java.lang.reflect.Method;

import net.minecraft.entity.projectile.EntityArrow;

/** 实装Galacticraft的只读箭重力查询；没有该模组时使用原生EntityArrow的float常量 */
public final class NativeArrowGravity {

    private static final Method GALACTICRAFT = resolve();

    private NativeArrowGravity() {}

    public static float forArrow(EntityArrow arrow) {
        if (GALACTICRAFT == null) return .05F;
        try {
            return ((Float) GALACTICRAFT.invoke(null, arrow)).floatValue();
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
            return null;
        } catch (NoSuchMethodException incompatible) {
            throw new IllegalStateException("Installed arrow gravity API differs", incompatible);
        }
    }
}
