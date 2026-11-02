package com.tianluoqaq.tlmnh.compat.battlegear2.client.render;

import net.minecraft.item.ItemStack;
import net.minecraftforge.client.IItemRenderer;

import org.joml.Matrix4f;

import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldItems;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.compat.battlegear2.common.shield.BattlegearShieldAccess;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 原生BG盾几何的握持坐标适配；原ItemRenderer、Forge helper及ShieldRenderer继续绘制实际物品 */
@SideOnly(Side.CLIENT)
public final class BattlegearShieldRenderTransforms {

    private BattlegearShieldRenderTransforms() {}

    /** 未知renderer沿原路径；已知外部合同失效时返回null，调用方只跳过这只手 */
    public static Matrix4f adapt(ItemStack stack, IItemRenderer renderer, boolean blocking, Matrix4f unchanged) {
        if (!BattlegearShieldAccess.supports(MaidShieldItems.itemId(stack))) return unchanged;
        return IntegrationGuard.call(Side.CLIENT, ExternalFeature.SHIELDS, () -> {
            if (!Api.matches(stack, renderer)) return unchanged;
            if (renderer.shouldUseRenderHelper(
                IItemRenderer.ItemRenderType.EQUIPPED,
                stack,
                IItemRenderer.ItemRendererHelper.EQUIPPED_BLOCK))
                throw new IllegalStateException("Native BG shield changed its equipped helper contract");
            return nativeIconBridge(blocking);
        }, () -> null);
    }

    /**
     * TLM交付的手部坐标已经包含其Bedrock/Gecko尾部变换，不再借用BG玩家肩部姿态
     * MC1.20.1 shield.json及shield_blocking.json的左手display先由ItemTransform反转X平移和Y/Z角度
     */
    public static Matrix4f nativeIconBridge(boolean blocking) {
        var matrix = blocking ? new Matrix4f().translate(-13.51F / 16, 3F / 16, 5F / 16)
            .rotateX(radians(45))
            .rotateY(radians(-135))
            : new Matrix4f().translate(-10F / 16, 6F / 16, 12F / 16)
                .rotateY(radians(-90));
        // ItemRenderer中心平移及BlockEntityWithoutLevelRenderer的盾模型轴向
        matrix.translate(-.5F, -.5F, -.5F)
            .scale(1, -1, -1);
        // ShieldModel板盒[-6,-11,-2]、大小[12,22,1]，背面位于Z=-1/16
        // 握柄盒[-1,-3,-1]、大小[2,6,6]，中心Z=2/16；板面与握柄中心必须留出3/16间距
        // BG背板中心位于(.5,.5,1/256)，将该板面映射到原板背面，不能放进握柄/手臂位置
        // 保留BG原第三人称10/16与Forge helper 1.5相乘后的等比尺寸，不拉伸成现代盾外形
        float nativeScale = 10F / 16 * 1.5F;
        matrix.translate(0, 0, -1F / 16)
            .scale(nativeScale, -nativeScale, nativeScale)
            .translate(-.5F, -.5F, -1F / 256);
        // native helper照常执行；在进入它之前明确抵消其坐标，避免再把板面当通用图标旋转
        return matrix.mul(equippedIconHelper().invert());
    }

    private static Matrix4f equippedIconHelper() {
        // ForgeHooksClient.renderEquippedItem中EQUIPPED_BLOCK=false的原生入口
        return new Matrix4f().translate(0, -.3F, 0)
            .scale(1.5F)
            .rotateY(radians(50))
            .rotateZ(radians(335))
            .translate(-.9375F, -.0625F, 0);
    }

    private static float radians(float degrees) {
        return (float) Math.toRadians(degrees);
    }

    private static final class Api {

        static boolean matches(ItemStack stack, IItemRenderer renderer) {
            return stack.getItem()
                .getClass() == mods.battlegear2.items.ItemShield.class && renderer != null
                && renderer.getClass() == mods.battlegear2.client.renderer.ShieldRenderer.class;
        }
    }
}
