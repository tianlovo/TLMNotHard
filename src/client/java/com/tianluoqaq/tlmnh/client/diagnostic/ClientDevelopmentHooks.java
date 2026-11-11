package com.tianluoqaq.tlmnh.client.diagnostic;

import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.client.render.model.BuiltinMaidModel;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame;
import com.tianluoqaq.tlmnh.common.life.bed.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.life.cabinet.TileEntitySnackCabinet;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineTile;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 客户端渲染与界面观察点契约，随 P3 一起归入客户端源集 */
@SideOnly(Side.CLIENT)
public interface ClientDevelopmentHooks {

    ClientDevelopmentHooks NONE = new ClientDevelopmentHooks() {};

    default ItemStack createDetailsItemFixture(String action, JsonObject construction) throws Exception {
        throw new IllegalStateException("Development item fixtures unavailable without tlmnhdevtools");
    }

    default JsonObject captureVisibleState(EntityMaid maid) {
        return new JsonObject();
    }

    default void injectCharacterInput(char character) throws ReflectiveOperationException {}

    default void headAfterBedrock(BuiltinMaidModel model, EntityMaid maid, float rawYaw, float rawPitch,
        float partialTick) {}

    default void headBeforePlayer(BuiltinMaidModel model, EntityMaid maid, MolangQueryFrame query, float rawYaw,
        float rawPitch) {}

    default void headAfterPlayer(BuiltinMaidModel model, EntityMaid maid, MolangQueryFrame query, float rawYaw,
        float rawPitch, float partialTick, boolean applied) {}

    default void headFailed(EntityMaid maid, RuntimeException error) {}

    default void gpuMatrixAfterPose(BuiltinMaidModel model, EntityMaid maid, float partialTick) {}

    default void submissionBegin(BuiltinMaidModel model, Entity entity, int light) {}

    default void submissionBeforeDraw() {}

    default void submissionDrawn(int returnedBytes) {}

    default boolean submissionDropVertex() {
        return false;
    }

    default float submissionModifyU(float u) {
        return u;
    }

    default void submissionVertex(float x, float y, float z, float red, float green, float blue, float alpha, float u,
        float v, int overlay, int lightmap, float normalX, float normalY, float normalZ) {}

    default void submissionFinish(boolean completed) {}

    default void heldGpuBeforeMaidBody(EntityMaid maid, BuiltinMaidModel model) {}

    default boolean heldGpuIsCapturing(EntityMaid maid) {
        return false;
    }

    default boolean heldGpuAtEquippedItems(EntityMaid maid, BuiltinMaidModel model, float partialTick, Runnable draw) {
        return false;
    }

    default void maidSleepRenderCompleted(EntityMaid maid, int direction, float rotation) {}

    default void tombstoneRenderCompleted(MaidTombstoneEntity tombstone, Object model, ResourceLocation texture,
        int nameLines) {}

    default void maidBedRenderCompleted(TileEntityMaidBed bed, Object model, ResourceLocation texture) {}

    default void shrineRenderCompleted(MaidShrineTile shrine, Object model, boolean itemDrawn) {}

    default void cabinetRenderCompleted(TileEntitySnackCabinet cabinet, Object model, int type, boolean full,
        boolean half) {}
}
