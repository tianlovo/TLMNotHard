package com.tianluoqaq.tlmnh.compat.tinkersdefense.client.render;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import net.minecraft.item.ItemStack;
import net.minecraftforge.client.IItemRenderer;

import org.joml.Matrix4f;

import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldItems;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.compat.battlegear2.client.render.BattlegearShieldRenderTransforms;
import com.tianluoqaq.tlmnh.compat.tinkersdefense.common.shield.TinkersDefenseShields;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** TD的FlexibleToolRenderer仍绘制原物品，只转换其实际XY盾板到女仆手部盾姿态。 */
@SideOnly(Side.CLIENT)
public final class TinkersDefenseShieldRenderTransforms {

    private TinkersDefenseShieldRenderTransforms() {}

    public static Matrix4f adapt(ItemStack stack, IItemRenderer renderer, boolean blocking, Matrix4f unchanged) {
        if (!TinkersDefenseShields.supports(MaidShieldItems.itemId(stack))) return unchanged;
        return IntegrationGuard.call(Side.CLIENT, ExternalFeature.SHIELDS, () -> {
            Api.requireNativePlate(stack, renderer);
            if (renderer.shouldUseRenderHelper(
                IItemRenderer.ItemRenderType.EQUIPPED,
                stack,
                IItemRenderer.ItemRendererHelper.EQUIPPED_BLOCK))
                throw new IllegalStateException("Native TD shield changed its equipped helper contract");
            return nativeIconBridge(blocking);
        }, () -> null);
    }

    public static Matrix4f nativeIconBridge(boolean blocking) {
        // 两者实际板面均为XY[0,1]；TD背面Z=0，正面Z=renderer.depth（实装默认1/32）。
        // BG已验桥以背面Z=1/256对齐现代盾背板，TD只需修正这个原生几何锚点。
        // B_TD = B_BG * H * Tz(1/256) * inverse(H)，H仍由Forge正常执行。
        // 厚度保留在背面外侧，不把盾板中心/正面错误地放到握柄与袖口内。
        var helper = equippedIconHelper();
        return BattlegearShieldRenderTransforms.nativeIconBridge(blocking)
            .mul(helper)
            .translate(0, 0, 1F / 256)
            .mul(new Matrix4f(helper).invert());
    }

    private static Matrix4f equippedIconHelper() {
        return new Matrix4f().translate(0, -.3F, 0)
            .scale(1.5F)
            .rotateY((float) Math.toRadians(50))
            .rotateZ((float) Math.toRadians(335))
            .translate(-.9375F, -.0625F, 0);
    }

    private static final class Api {

        private static final Class<?> ROUND = resolve("gmail.Lance5057.items.RoundShield"),
            HEATER = resolve("gmail.Lance5057.items.HeaterShield"),
            RENDERER = resolve("tconstruct.client.FlexibleToolRenderer");
        private static final Field DEPTH = resolveDepth();

        static void requireNativePlate(ItemStack stack, IItemRenderer renderer) throws IllegalAccessException {
            var item = stack.getItem()
                .getClass();
            if ((item != ROUND && item != HEATER) || renderer == null || renderer.getClass() != RENDERER)
                throw new IllegalStateException(
                    "Native TD shield item or equipped renderer differs from the plate contract");
            float depth = DEPTH.getFloat(renderer);
            if (!Float.isFinite(depth) || depth <= 0)
                throw new IllegalStateException("Native TD shield has an invalid plate depth");
        }

        private static Class<?> resolve(String name) {
            try {
                return Class.forName(name, false, TinkersDefenseShieldRenderTransforms.class.getClassLoader());
            } catch (ClassNotFoundException failure) {
                throw new IllegalStateException("Native TD shield rendering class missing: " + name, failure);
            }
        }

        private static Field resolveDepth() {
            try {
                var field = RENDERER.getField("depth");
                if (field.getDeclaringClass() != RENDERER || field.getType() != float.class
                    || Modifier.isStatic(field.getModifiers()))
                    throw new IllegalStateException("Native TD shield renderer depth signature differs");
                return field;
            } catch (NoSuchFieldException failure) {
                throw new IllegalStateException("Native TD shield renderer depth field missing", failure);
            }
        }
    }
}
