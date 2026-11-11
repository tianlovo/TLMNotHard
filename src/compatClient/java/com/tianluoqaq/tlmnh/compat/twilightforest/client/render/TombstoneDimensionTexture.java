package com.tianluoqaq.tlmnh.compat.twilightforest.client.render;

import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 通过暮色森林的维度 Provider 类型识别墓碑专属贴图，不写死玩家可改的维度 ID */
@SideOnly(Side.CLIENT)
public final class TombstoneDimensionTexture {

    private static final ResourceLocation TEXTURE = new ResourceLocation(
        TwilightForestClientRenderIds.TEXTURE_TOMBSTONE_TWILIGHT_FOREST);
    private static volatile Class<?> providerType;

    private TombstoneDimensionTexture() {}

    public static ResourceLocation resolve(World world) {
        if (!Loader.isModLoaded(KnownModIds.TWILIGHT_FOREST)) return null;
        return IntegrationGuard.call(Side.CLIENT, ExternalFeature.CLIENT_TOMBSTONE_DIMENSION_TEXTURE, () -> {
            var resolved = providerType;
            if (resolved == null) {
                resolved = OptionalApi.findClass(
                    KnownModIds.TWILIGHT_FOREST,
                    TwilightForestClientRenderIds.ID_TWILIGHTFOREST_WORLD_WORLDPROVIDERTWILIGHTFOREST,
                    world.provider.getClass()
                        .getClassLoader());
                providerType = resolved;
            }
            return resolved != null && resolved.isInstance(world.provider) ? TEXTURE : null;
        }, () -> null);
    }
}
