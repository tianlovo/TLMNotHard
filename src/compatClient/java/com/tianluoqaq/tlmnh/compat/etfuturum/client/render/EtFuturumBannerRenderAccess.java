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
            && stack.getItem() == GameRegistry.findItem(EtFuturumClientRenderIds.ID_ETFUTURUM, EtFuturumClientRenderIds.ID_BANNER);
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
                ITEM = Class.forName(EtFuturumClientRenderReflectionSpec.TARGET_CLASS_ITEM_BLOCK_BANNER);
                RENDERER = Class.forName(EtFuturumClientRenderReflectionSpec.TARGET_CLASS_TILE_ENTITY_BANNER_RENDERER);
                var tile = Class.forName(EtFuturumClientRenderReflectionSpec.TARGET_CLASS_TILE_ENTITY_BANNER)
                    .asSubclass(TileEntity.class);
                NEW_TILE = tile.getConstructor();
                SET_ITEM = tile.getMethod(EtFuturumClientRenderIds.ID_SETITEMVALUES, ItemStack.class);
                GET_PATTERNS = tile.getMethod(EtFuturumClientRenderIds.ID_GETPATTERNLIST);
                GET_COLORS = tile.getMethod(EtFuturumClientRenderIds.ID_GETCOLORLIST);
                PATTERN_ID = Class.forName(tile.getName() + EtFuturumClientRenderIds.ID_ENUMBANNERPATTERN)
                    .getMethod(EtFuturumClientRenderIds.ID_GETPATTERNID);
                COLOR_DAMAGE = Class.forName(EtFuturumClientRenderReflectionSpec.TARGET_CLASS_ENUM_COLOR)
                    .getMethod(EtFuturumClientRenderIds.ID_GETDAMAGE);
                TEXTURE = RENDERER.getDeclaredMethod(EtFuturumClientRenderIds.ID_GETTEXTURE, tile);
                if (TEXTURE.getReturnType() != ResourceLocation.class || SET_ITEM.getReturnType() != void.class
                    || GET_PATTERNS.getReturnType() != List.class
                    || GET_COLORS.getReturnType() != List.class
                    || PATTERN_ID.getReturnType() != String.class
                    || COLOR_DAMAGE.getReturnType() != int.class)
                    throw new IllegalStateException(EtFuturumClientRenderDiagnostics.MSG_NATIVE_BANNER_TEXTURE_RETURN_CONTRACT);
                TEXTURE.setAccessible(true);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(EtFuturumClientRenderDiagnostics.MSG_NATIVE_BANNER_TEXTURE_ENTRY_IS, failure);
            }
        }

        static ResourceLocation texture(ItemStack original) throws ReflectiveOperationException {
            if (!isBanner(original) || original.getItem()
                .getClass() != ITEM) throw new IllegalStateException(EtFuturumClientRenderDiagnostics.MSG_NATIVE_BANNER_ITEM_IDENTITY_CHANGED);
            var display = original.copy();
            var banner = NEW_TILE.newInstance();
            // 完整物品副本先交给真实Tile API，Base、Patterns、颜色和未知输入继续由提供方解释
            SET_ITEM.invoke(banner, display);
            var patterns = (List<?>) GET_PATTERNS.invoke(banner);
            var colors = (List<?>) GET_COLORS.invoke(banner);
            if (patterns.size() != colors.size())
                throw new IllegalStateException(EtFuturumClientRenderDiagnostics.MSG_NATIVE_BANNER_PATTERN_COLOR_COUNT);
            if (patterns.size() > EtFuturumClientRenderLayout.MAX_SIZE_THRESHOLD) {
                // 原LayerMaidBanner最多画底色及16个已解析图案；只裁剪显示副本，完整原NBT不动
                var visible = new NBTTagList();
                for (int index = 1; index < EtFuturumClientRenderLayout.MAX_SIZE_THRESHOLD; index++) {
                    var entry = new NBTTagCompound();
                    entry.setString(EtFuturumClientRenderIds.KEY_PATTERN, (String) PATTERN_ID.invoke(patterns.get(index)));
                    entry.setInteger(EtFuturumClientRenderIds.KEY_COLOR, (Integer) COLOR_DAMAGE.invoke(colors.get(index)));
                    visible.appendTag(entry);
                }
                var tag = display.hasTagCompound() ? (NBTTagCompound) display.getTagCompound()
                    .copy() : new NBTTagCompound();
                var block = (NBTTagCompound) tag.getCompoundTag(EtFuturumClientRenderIds.KEY_BLOCK_ENTITY_TAG)
                    .copy();
                block.setTag(EtFuturumClientRenderIds.KEY_PATTERNS, visible);
                tag.setTag(EtFuturumClientRenderIds.KEY_BLOCK_ENTITY_TAG, block);
                display.setTagCompound(tag);
                SET_ITEM.invoke(banner, display);
            }
            var renderer = TileEntityRendererDispatcher.instance.getSpecialRenderer(banner);
            if (renderer == null || renderer.getClass() != RENDERER)
                throw new IllegalStateException(EtFuturumClientRenderDiagnostics.MSG_NATIVE_BANNER_RENDERER_IDENTITY_CHANGED);
            return (ResourceLocation) TEXTURE.invoke(renderer, banner);
        }
    }
}
