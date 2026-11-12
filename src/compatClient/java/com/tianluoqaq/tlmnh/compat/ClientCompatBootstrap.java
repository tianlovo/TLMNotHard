package com.tianluoqaq.tlmnh.compat;

import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.client.IItemRenderer;

import org.joml.Matrix4f;

import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.client.compat.ClientCompatRegistrar;
import com.tianluoqaq.tlmnh.client.compat.ClientCompatServices;
import com.tianluoqaq.tlmnh.client.compat.spi.ClientItemRenderCompat;
import com.tianluoqaq.tlmnh.client.compat.spi.RenderPlatformCompat;
import com.tianluoqaq.tlmnh.common.clienthook.HandSlotIconHook;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;
import com.tianluoqaq.tlmnh.compat.angelica.client.render.MaidRenderPlatformState;
import com.tianluoqaq.tlmnh.compat.backhand.client.BackhandPlayerItems;
import com.tianluoqaq.tlmnh.compat.battlegear2.Battlegear2Compat;
import com.tianluoqaq.tlmnh.compat.battlegear2.client.gui.BattlegearSlotIcons;
import com.tianluoqaq.tlmnh.compat.battlegear2.client.render.BattlegearShieldRenderTransforms;
import com.tianluoqaq.tlmnh.compat.etfuturum.client.render.EtFuturumBannerRenderAccess;
import com.tianluoqaq.tlmnh.compat.etfuturum.client.render.EtFuturumSkullRenderAccess;
import com.tianluoqaq.tlmnh.compat.tinkersdefense.TinkersDefenseCompat;
import com.tianluoqaq.tlmnh.compat.tinkersdefense.client.render.TinkersDefenseShieldRenderTransforms;
import com.tianluoqaq.tlmnh.compat.twilightforest.client.render.TombstoneDimensionTexture;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 客户端专属适配实现装配入口，在客户端启动时注册渲染平台、手持盾牌矩阵与槽位图标钩子 */
@SideOnly(Side.CLIENT)
public final class ClientCompatBootstrap implements ClientCompatRegistrar {

    @Override
    public void register(ClientCompatServices.Sink sink) {
        HandSlotIconHook.install(
            offhand -> OptionalApi.isModLoaded(Battlegear2Compat.MOD_ID) ? BattlegearSlotIcons.hand(offhand) : null);
        sink.renderPlatform(new RenderPlatformImpl())
            .itemRender(new ClientItemRenderImpl());
    }

    private static final class RenderPlatformImpl implements RenderPlatformCompat {

        @Override
        public void preflight() {
            MaidRenderPlatformState.preflight();
        }

        @Override
        public int matrixDepth(int compatibilityName) {
            return MaidRenderPlatformState.matrixDepth(compatibilityName);
        }

        @Override
        public FilterWindowHandle captureFilterWindow() {
            return MaidRenderPlatformState.captureFilterWindow();
        }
    }

    private static final class ClientItemRenderImpl implements ClientItemRenderCompat {

        @Override
        public ItemStack playerOffhandStack(EntityPlayer player) {
            return BackhandPlayerItems.offhand(player);
        }

        @Override
        public ResourceLocation resolveTombstoneTexture(World world) {
            return TombstoneDimensionTexture.resolve(world);
        }

        @Override
        public boolean isBanner(ItemStack stack) {
            return EtFuturumBannerRenderAccess.isBanner(stack);
        }

        @Override
        public ResourceLocation bannerTexture(ItemStack stack) {
            return EtFuturumBannerRenderAccess.texture(stack);
        }

        @Override
        public void renderCustomSkull(TileEntitySpecialRenderer renderer, int skullType, GameProfile profile) {
            EtFuturumSkullRenderAccess.render(renderer, skullType, profile);
        }

        @Override
        public Matrix4f adaptOffhandShieldTransform(ItemStack stack, IItemRenderer renderer, boolean blocking,
            Matrix4f bridge) {
            Matrix4f adapted = bridge;
            if (OptionalApi.isModLoaded(Battlegear2Compat.MOD_ID)) {
                adapted = BattlegearShieldRenderTransforms.adapt(stack, renderer, blocking, adapted);
                if (adapted == null) return null;
            }
            if (OptionalApi.isModLoaded(TinkersDefenseCompat.MOD_ID)) {
                adapted = TinkersDefenseShieldRenderTransforms.adapt(stack, renderer, blocking, adapted);
            }
            return adapted;
        }
    }
}
