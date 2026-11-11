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

/** TD的FlexibleToolRenderer仍绘制原物品，只转换其实际XY盾板到女仆手部盾姿态 */
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
                throw new IllegalStateException(TinkersDefenseClientRenderDiagnostics.MSG_NATIVE_TD_SHIELD_CHANGED_ITS);
            return nativeIconBridge(blocking);
        }, () -> null);
    }

    public static Matrix4f nativeIconBridge(boolean blocking) {
        // 两者实际板面均为XY[0,1]；TD背面Z=0，正面Z=renderer.depth（实装默认1/32）
        // BG已验桥以背面Z=1/256对齐现代盾背板，TD只需修正这个原生几何锚点
        // B_TD = B_BG * H * Tz(1/256) * inverse(H)，H仍由Forge正常执行
        // 厚度保留在背面外侧，不把盾板中心/正面错误地放到握柄与袖口内
        var helper = equippedIconHelper();
        return BattlegearShieldRenderTransforms.nativeIconBridge(blocking)
            .mul(helper)
            .translate(TinkersDefenseClientRenderLayout.TINKERS_DEFENSE_NATIVE_ICON_BRIDGE_OFFSET_X, TinkersDefenseClientRenderLayout.TINKERS_DEFENSE_NATIVE_ICON_BRIDGE_OFFSET_X, TinkersDefenseClientRenderLayout.TINKERS_DEFENSE_NATIVE_ICON_BRIDGE_OFFSET_Z / TinkersDefenseClientRenderLayout.TINKERS_DEFENSE_NATIVE_ICON_BRIDGE_OFFSET_Z_2)
            .mul(new Matrix4f(helper).invert());
    }

    private static Matrix4f equippedIconHelper() {
        return new Matrix4f().translate(TinkersDefenseClientRenderLayout.TINKERS_DEFENSE_NATIVE_ICON_BRIDGE_OFFSET_X, TinkersDefenseClientRenderLayout.TINKERS_DEFENSE_MATRIX4F_OFFSET_Y, TinkersDefenseClientRenderLayout.TINKERS_DEFENSE_NATIVE_ICON_BRIDGE_OFFSET_X)
            .scale(TinkersDefenseClientRenderLayout.TINKERS_DEFENSE_RENDER_SCALE_X)
            .rotateY((float) Math.toRadians(TinkersDefenseClientRenderLayout.TINKERS_DEFENSE_TO_RADIANS_FIRST_PARAM))
            .rotateZ((float) Math.toRadians(TinkersDefenseClientRenderLayout.TINKERS_DEFENSE_TO_RADIANS_FIRST_PARAM_2))
            .translate(TinkersDefenseClientRenderLayout.TINKERS_DEFENSE_RENDER_OFFSET_X, TinkersDefenseClientRenderLayout.TINKERS_DEFENSE_RENDER_OFFSET_Y, TinkersDefenseClientRenderLayout.TINKERS_DEFENSE_NATIVE_ICON_BRIDGE_OFFSET_X);
    }

    private static final class Api {

        private static final Class<?> ROUND = resolve(TinkersDefenseClientRenderIds.ID_GMAIL_LANCE5057_ITEMS_ROUNDSHIELD),
            HEATER = resolve(TinkersDefenseClientRenderIds.ID_GMAIL_LANCE5057_ITEMS_HEATERSHIELD),
            RENDERER = resolve(TinkersDefenseClientRenderReflectionSpec.TARGET_CLASS_FLEXIBLE_TOOL_RENDERER);
        private static final Field DEPTH = resolveDepth();

        static void requireNativePlate(ItemStack stack, IItemRenderer renderer) throws IllegalAccessException {
            var item = stack.getItem()
                .getClass();
            if ((item != ROUND && item != HEATER) || renderer == null || renderer.getClass() != RENDERER)
                throw new IllegalStateException(
                    TinkersDefenseClientRenderDiagnostics.MSG_NATIVE_TD_SHIELD_ITEM_OR);
            float depth = DEPTH.getFloat(renderer);
            if (!Float.isFinite(depth) || depth <= 0)
                throw new IllegalStateException(TinkersDefenseClientRenderDiagnostics.MSG_NATIVE_TD_SHIELD_HAS_AN);
        }

        private static Class<?> resolve(String name) {
            try {
                return Class.forName(name, false, TinkersDefenseShieldRenderTransforms.class.getClassLoader());
            } catch (ClassNotFoundException failure) {
                throw new IllegalStateException(TinkersDefenseClientRenderDiagnostics.MSG_NATIVE_TD_SHIELD_RENDERING_CLASS + name, failure);
            }
        }

        private static Field resolveDepth() {
            try {
                var field = RENDERER.getField(TinkersDefenseClientRenderIds.ID_DEPTH);
                if (field.getDeclaringClass() != RENDERER || field.getType() != float.class
                    || Modifier.isStatic(field.getModifiers()))
                    throw new IllegalStateException(TinkersDefenseClientRenderDiagnostics.MSG_NATIVE_TD_SHIELD_RENDERER_DEPTH);
                return field;
            } catch (NoSuchFieldException failure) {
                throw new IllegalStateException(TinkersDefenseClientRenderDiagnostics.MSG_NATIVE_TD_SHIELD_RENDERER_DEPTH_2, failure);
            }
        }
    }
}
