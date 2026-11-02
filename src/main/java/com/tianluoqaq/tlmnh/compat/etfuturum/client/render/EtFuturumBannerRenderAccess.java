package com.tianluoqaq.tlmnh.compat.etfuturum.client.render;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;

import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 真实EFR旗帜提供方的只读显示边界；原物品不交给外部代码，未知接口不猜测替代 */
@SideOnly(Side.CLIENT)
public final class EtFuturumBannerRenderAccess {

    private EtFuturumBannerRenderAccess() {}

    /** 路由只认目标实际注册物品；显示熔断后也不能落入不带图案NBT的通用头饰路径 */
    public static boolean isBanner(ItemStack stack) {
        return stack != null && stack.getItem() != null
            && stack.getItem() == GameRegistry.findItem("etfuturum", "banner");
    }

    /** null也可能是提供方256项缓存暂满；此时沿其原规则跳过本帧，不重建或清空外部缓存 */
    public static ResourceLocation texture(ItemStack stack) {
        return IntegrationGuard.call(Side.CLIENT, ExternalFeature.CLIENT_BANNER, () -> Api.texture(stack), () -> null);
    }

    private static final class Api {

        private static final Class<?> ITEM, RENDERER;
        private static final Constructor<? extends TileEntity> NEW_TILE;
        private static final Method SET_ITEM, GET_PATTERNS, GET_COLORS, PATTERN_ID, COLOR_DAMAGE, TEXTURE;

        static {
            try {
                ITEM = Class.forName("ganymedes01.etfuturum.blocks.itemblocks.ItemBlockBanner");
                RENDERER = Class.forName("ganymedes01.etfuturum.client.renderer.tileentity.TileEntityBannerRenderer");
                var tile = Class.forName("ganymedes01.etfuturum.tileentities.TileEntityBanner")
                    .asSubclass(TileEntity.class);
                NEW_TILE = tile.getConstructor();
                SET_ITEM = tile.getMethod("setItemValues", ItemStack.class);
                GET_PATTERNS = tile.getMethod("getPatternList");
                GET_COLORS = tile.getMethod("getColorList");
                PATTERN_ID = Class.forName(tile.getName() + "$EnumBannerPattern")
                    .getMethod("getPatternID");
                COLOR_DAMAGE = Class.forName("ganymedes01.etfuturum.lib.EnumColor")
                    .getMethod("getDamage");
                TEXTURE = RENDERER.getDeclaredMethod("getTexture", tile);
                if (TEXTURE.getReturnType() != ResourceLocation.class || SET_ITEM.getReturnType() != void.class
                    || GET_PATTERNS.getReturnType() != List.class
                    || GET_COLORS.getReturnType() != List.class
                    || PATTERN_ID.getReturnType() != String.class
                    || COLOR_DAMAGE.getReturnType() != int.class)
                    throw new IllegalStateException("Native banner texture return contract changed");
                TEXTURE.setAccessible(true);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native banner texture entry is unavailable", failure);
            }
        }

        static ResourceLocation texture(ItemStack original) throws ReflectiveOperationException {
            if (!isBanner(original) || original.getItem()
                .getClass() != ITEM) throw new IllegalStateException("Native banner item identity changed");
            var display = original.copy();
            var banner = NEW_TILE.newInstance();
            // 完整物品副本先交给真实Tile API，Base、Patterns、颜色和未知输入继续由提供方解释
            SET_ITEM.invoke(banner, display);
            var patterns = (List<?>) GET_PATTERNS.invoke(banner);
            var colors = (List<?>) GET_COLORS.invoke(banner);
            if (patterns.size() != colors.size())
                throw new IllegalStateException("Native banner pattern/color count changed");
            if (patterns.size() > 17) {
                // 原LayerMaidBanner最多画底色及16个已解析图案；只裁剪显示副本，完整原NBT不动
                var visible = new NBTTagList();
                for (int index = 1; index < 17; index++) {
                    var entry = new NBTTagCompound();
                    entry.setString("Pattern", (String) PATTERN_ID.invoke(patterns.get(index)));
                    entry.setInteger("Color", (Integer) COLOR_DAMAGE.invoke(colors.get(index)));
                    visible.appendTag(entry);
                }
                var tag = display.hasTagCompound() ? (NBTTagCompound) display.getTagCompound()
                    .copy() : new NBTTagCompound();
                var block = (NBTTagCompound) tag.getCompoundTag("BlockEntityTag")
                    .copy();
                block.setTag("Patterns", visible);
                tag.setTag("BlockEntityTag", block);
                display.setTagCompound(tag);
                SET_ITEM.invoke(banner, display);
            }
            var renderer = TileEntityRendererDispatcher.instance.getSpecialRenderer(banner);
            if (renderer == null || renderer.getClass() != RENDERER)
                throw new IllegalStateException("Native banner renderer identity changed");
            return (ResourceLocation) TEXTURE.invoke(renderer, banner);
        }
    }
}
