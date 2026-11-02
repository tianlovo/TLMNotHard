package com.tianluoqaq.tlmnh.compat.twilightforest.client.render;

import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 使用实装提供方的类型身份，避免把可配置的暮色维度号写死 */
@SideOnly(Side.CLIENT)
public final class TombstoneDimensionTexture {

    private static final ResourceLocation TEXTURE = new ResourceLocation(
        "touhou_little_maid:textures/bedrock/entity/tombstone/tombstone_twilight_forest.png");
    private static Class<?> providerType;

    private TombstoneDimensionTexture() {}

    public static ResourceLocation resolve(World world) {
        if (!Loader.isModLoaded("TwilightForest")) return null;
        return IntegrationGuard.call(Side.CLIENT, ExternalFeature.CLIENT_TOMBSTONE_DIMENSION_TEXTURE, () -> {
            if (providerType == null) providerType = Class.forName(
                "twilightforest.world.WorldProviderTwilightForest",
                false,
                world.provider.getClass()
                    .getClassLoader());
            return providerType.isInstance(world.provider) ? TEXTURE : null;
        }, () -> null);
    }
}
