package com.tianluoqaq.tlmnh.devtools;

import java.io.IOException;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.client.development.ClientDevelopmentControl;
import com.tianluoqaq.tlmnh.client.development.GpuMatrixProbe;
import com.tianluoqaq.tlmnh.client.development.HeadBindingProbe;
import com.tianluoqaq.tlmnh.client.development.HeldItemGpuProbe;
import com.tianluoqaq.tlmnh.client.development.MaidDetailsItemFixtures;
import com.tianluoqaq.tlmnh.client.development.RenderSubmissionProbe;
import com.tianluoqaq.tlmnh.client.development.life.CabinetRenderObservation;
import com.tianluoqaq.tlmnh.client.development.life.MaidBedRenderObservation;
import com.tianluoqaq.tlmnh.client.development.life.MaidSleepRenderObservation;
import com.tianluoqaq.tlmnh.client.development.life.ShrineRenderObservation;
import com.tianluoqaq.tlmnh.client.development.lifecycle.TombstoneRenderObservation;
import com.tianluoqaq.tlmnh.client.diagnostic.ClientDevelopmentAccess;
import com.tianluoqaq.tlmnh.client.diagnostic.ClientDevelopmentHooks;
import com.tianluoqaq.tlmnh.client.render.BuiltinMaidModel;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame;
import com.tianluoqaq.tlmnh.common.development.interaction.MaidVisibleStateSnapshot;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.life.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.life.TileEntitySnackCabinet;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineTile;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity;
import com.tianluoqaq.tlmnh.common.registry.EventBuses;
import com.tianluoqaq.tlmnh.compat.lwjgl3ify.development.input.LwjglTextInput;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 客户端观察点桥接与控制通道挂载，与服务端启动路径隔离 */
@SideOnly(Side.CLIENT)
final class DevtoolsClientBootstrap {

    private DevtoolsClientBootstrap() {}

    static void installHooks() {
        ClientDevelopmentAccess.install(Hooks.INSTANCE);
    }

    static void initClientControl() {
        if (!DevelopmentFlags.isDevCommandsEnabled()) return;
        try {
            EventBuses.registerFml(new ClientDevelopmentControl(Minecraft.getMinecraft()));
        } catch (IOException error) {
            throw new IllegalStateException("Cannot initialize client development controls", error);
        }
    }

    private enum Hooks implements ClientDevelopmentHooks {

        INSTANCE;

        @Override
        public ItemStack createDetailsItemFixture(String action, JsonObject construction) throws Exception {
            return MaidDetailsItemFixtures.create(action, construction);
        }

        @Override
        public JsonObject captureVisibleState(EntityMaid maid) {
            return MaidVisibleStateSnapshot.capture(maid);
        }

        @Override
        public void injectCharacterInput(char character) throws ReflectiveOperationException {
            LwjglTextInput.character(character);
        }

        @Override
        public void headAfterBedrock(BuiltinMaidModel model, EntityMaid maid, float rawYaw, float rawPitch,
            float partialTick) {
            HeadBindingProbe.afterBedrock(model, maid, rawYaw, rawPitch, partialTick);
        }

        @Override
        public void headBeforePlayer(BuiltinMaidModel model, EntityMaid maid, MolangQueryFrame query, float rawYaw,
            float rawPitch) {
            HeadBindingProbe.beforePlayer(model, maid, query, rawYaw, rawPitch);
        }

        @Override
        public void headAfterPlayer(BuiltinMaidModel model, EntityMaid maid, MolangQueryFrame query, float rawYaw,
            float rawPitch, float partialTick, boolean applied) {
            HeadBindingProbe.afterPlayer(model, maid, query, rawYaw, rawPitch, partialTick, applied);
        }

        @Override
        public void headFailed(EntityMaid maid, RuntimeException error) {
            HeadBindingProbe.failed(maid, error);
        }

        @Override
        public void gpuMatrixAfterPose(BuiltinMaidModel model, EntityMaid maid, float partialTick) {
            GpuMatrixProbe.afterPose(model, maid, partialTick);
        }

        @Override
        public void submissionBegin(BuiltinMaidModel model, Entity entity, int light) {
            RenderSubmissionProbe.begin(model, entity, light);
        }

        @Override
        public void submissionBeforeDraw() {
            RenderSubmissionProbe.beforeDraw();
        }

        @Override
        public void submissionDrawn(int returnedBytes) {
            RenderSubmissionProbe.drawn(returnedBytes);
        }

        @Override
        public boolean submissionDropVertex() {
            return RenderSubmissionProbe.dropVertex();
        }

        @Override
        public float submissionModifyU(float u) {
            return RenderSubmissionProbe.submittedU(u);
        }

        @Override
        public void submissionVertex(float x, float y, float z, float red, float green, float blue, float alpha,
            float u, float v, int overlay, int lightmap, float normalX, float normalY, float normalZ) {
            RenderSubmissionProbe
                .submitted(x, y, z, red, green, blue, alpha, u, v, overlay, lightmap, normalX, normalY, normalZ);
        }

        @Override
        public void submissionFinish(boolean completed) {
            RenderSubmissionProbe.finish(completed);
        }

        @Override
        public void heldGpuBeforeMaidBody(EntityMaid maid, BuiltinMaidModel model) {
            HeldItemGpuProbe.beforeMaidBody(maid, model);
        }

        @Override
        public boolean heldGpuIsCapturing(EntityMaid maid) {
            return HeldItemGpuProbe.isCapturing(maid);
        }

        @Override
        public boolean heldGpuAtEquippedItems(EntityMaid maid, BuiltinMaidModel model, float partialTick,
            Runnable draw) {
            return HeldItemGpuProbe.atEquippedItems(maid, model, partialTick, draw);
        }

        @Override
        public void maidSleepRenderCompleted(EntityMaid maid, int direction, float rotation) {
            MaidSleepRenderObservation.completed(maid, direction, rotation);
        }

        @Override
        public void tombstoneRenderCompleted(MaidTombstoneEntity tombstone, Object model, ResourceLocation texture,
            int nameLines) {
            TombstoneRenderObservation.completed(tombstone, model, texture, nameLines);
        }

        @Override
        public void maidBedRenderCompleted(TileEntityMaidBed bed, Object model, ResourceLocation texture) {
            MaidBedRenderObservation.completed(bed, model, texture);
        }

        @Override
        public void shrineRenderCompleted(MaidShrineTile shrine, Object model, boolean itemDrawn) {
            ShrineRenderObservation.completed(shrine, model, itemDrawn);
        }

        @Override
        public void cabinetRenderCompleted(TileEntitySnackCabinet cabinet, Object model, int type, boolean full,
            boolean half) {
            CabinetRenderObservation.completed(cabinet, model, type, full, half);
        }
    }
}
