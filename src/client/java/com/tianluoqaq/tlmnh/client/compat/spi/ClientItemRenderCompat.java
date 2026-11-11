package com.tianluoqaq.tlmnh.client.compat.spi;

import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.client.IItemRenderer;

import org.joml.Matrix4f;

import com.mojang.authlib.GameProfile;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 客户端头颅、旗帜、盾牌持握矩阵与维度墓碑贴图的窄适配契约 */
@SideOnly(Side.CLIENT)
public interface ClientItemRenderCompat {

    ClientItemRenderCompat NOOP = new ClientItemRenderCompat() {};

    default ResourceLocation resolveTombstoneTexture(World world) {
        return null;
    }

    default boolean isBanner(ItemStack stack) {
        return false;
    }

    default ResourceLocation bannerTexture(ItemStack stack) {
        return null;
    }

    default void renderCustomSkull(TileEntitySpecialRenderer renderer, int skullType, GameProfile profile) {
        throw new IllegalStateException(ClientCompatSpiDiagnostics.MSG_UNKNOWN_ACTIVE_SKULL_RENDERER_NATIVE);
    }

    default Matrix4f adaptOffhandShieldTransform(ItemStack stack, IItemRenderer renderer, boolean blocking,
        Matrix4f bridge) {
        return bridge;
    }
}
