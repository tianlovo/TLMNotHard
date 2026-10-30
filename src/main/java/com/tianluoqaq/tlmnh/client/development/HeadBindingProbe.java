package com.tianluoqaq.tlmnh.client.development;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.world.WorldSettings.GameType;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.client.render.BuiltinMaidModel;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 只在开发虚空场地观测一次真实实体到模型输入的角度绑定，不判断GPU成像 */
@SideOnly(Side.CLIENT)
public final class HeadBindingProbe {

    private static Request pending;

    private HeadBindingProbe() {}

    public static void arm(String request, String model, float yaw, float pitch, String fault) {
        if (!Boolean.getBoolean("tlmnh.devCommands")) throw new IllegalStateException("Head binding probe disabled");
        if (pending != null) throw new IllegalStateException("Head binding probe already pending");
        requireObserver();
        var minecraft = Minecraft.getMinecraft();
        EntityMaid selected = null;
        for (var entity : minecraft.theWorld.loadedEntityList) if (entity instanceof EntityMaid maid) {
            if (selected != null) throw new IllegalStateException("Exactly one actual lab maid required");
            selected = maid;
        }
        if (selected == null || !selected.isOwner(minecraft.thePlayer)
            || !selected.getModelId()
                .equals(model)
            || !selected.isEntityAlive()
            || selected.ridingEntity != null
            || selected.riddenByEntity != null
            || selected.getDistanceSqToEntity(minecraft.thePlayer) > 64
            || !Pose.capture(selected)
                .finite())
            throw new IllegalStateException("Requested sole owned unmounted lab maid required");
        var choice = BuiltinModelCatalog.get(model);
        if (choice == null || !Float.isFinite(yaw)
            || yaw < -85
            || yaw > 85
            || !Float.isFinite(pitch)
            || pitch < -90
            || pitch > 90
            || !(fault.equals("native") || fault.equals("legacy_sign")))
            throw new IllegalArgumentException("Unsupported head binding request");
        if (!choice.isGecko() && !fault.equals("native"))
            throw new IllegalArgumentException("Legacy sign fault requires a Gecko model");
        pending = new Request(request, model, yaw, pitch, fault, selected);
        pending.expected.apply(selected);
    }

    public static boolean isPending() {
        return pending != null;
    }

    /** 由ClientTick END调用，使下一次原生RendererLivingEntity从实际实体计算角度 */
    public static void tickEnd() {
        if (pending == null || pending.report != null) return;
        try {
            if (++pending.ticks > 200 || System.nanoTime() > pending.deadline)
                throw new IllegalStateException("Actual head render was not observed before deadline");
            validateActor();
            pending.expected.apply(pending.maid);
        } catch (RuntimeException error) {
            fail(error.toString());
        }
    }

    /** 故障发生在真正播放器消费query之前，正常路径不改变capture的结果 */
    public static void beforePlayer(BuiltinMaidModel model, EntityMaid maid, MolangQueryFrame query, float rawYaw,
        float rawPitch) {
        if (!matches(maid)) return;
        try {
            validateActor();
            requireActualModel(model);
            if (pending.fault.equals("legacy_sign")) {
                query.netHeadYaw = rawYaw;
                query.headPitch = rawPitch;
            }
        } catch (RuntimeException error) {
            fail(error.toString());
        }
    }

    public static void afterPlayer(BuiltinMaidModel model, EntityMaid maid, MolangQueryFrame query, float rawYaw,
        float rawPitch, float partialTick, boolean applied) {
        observe(model, maid, query, rawYaw, rawPitch, partialTick, applied);
    }

    public static void afterBedrock(BuiltinMaidModel model, EntityMaid maid, float rawYaw, float rawPitch,
        float partialTick) {
        observe(model, maid, null, rawYaw, rawPitch, partialTick, true);
    }

    private static void observe(BuiltinMaidModel model, EntityMaid maid, MolangQueryFrame query, float rawYaw,
        float rawPitch, float partialTick, boolean applied) {
        if (!matches(maid)) return;
        try {
            validateActor();
            requireActualModel(model);
            var actor = Pose.capture(maid);
            if (!Float.isFinite(rawYaw) || !Float.isFinite(rawPitch)
                || !Float.isFinite(partialTick)
                || query != null && (!Double.isFinite(query.netHeadYaw) || !Double.isFinite(query.headPitch)
                    || !Double.isFinite(query.bodyYaw)))
                throw new IllegalStateException("Non-finite actual head binding values");
            float expectedRawYaw = pending.expected.head - pending.expected.body;
            boolean actorMatched = pending.expected.same(actor);
            boolean rawMatched = rawYaw == expectedRawYaw && rawPitch == pending.pitch
                && Float.isFinite(partialTick)
                && partialTick >= 0
                && partialTick <= 1;
            boolean queryMatched = model.getChoice()
                .isGecko() == (query != null)
                && (query == null || query.netHeadYaw == -expectedRawYaw && query.headPitch == -pending.pitch
                    && query.bodyYaw == -180);
            var report = header();
            report.addProperty("status", "complete");
            report.addProperty("backend", query == null ? "bedrock" : "gecko");
            report.add("actorAtRender", actor.json());
            var raw = new JsonObject();
            raw.addProperty("netHeadYaw", rawYaw);
            raw.addProperty("headPitch", rawPitch);
            raw.addProperty("partialTick", partialTick);
            report.add("rawRender", raw);
            report.addProperty("queryCaptured", query != null);
            if (query != null) {
                var actual = new JsonObject();
                actual.addProperty("bodyYaw", query.bodyYaw);
                actual.addProperty("netHeadYaw", query.netHeadYaw);
                actual.addProperty("headPitch", query.headPitch);
                report.add("consumedQuery", actual);
            } else report.add("consumedRender", raw);
            report.addProperty("playerApplied", applied);
            report.addProperty("actorMatched", actorMatched);
            report.addProperty("rawRenderMatched", rawMatched);
            report.addProperty("queryMatched", queryMatched);
            report.addProperty("bindingMatched", actorMatched && rawMatched && queryMatched && applied);
            finish(report);
        } catch (RuntimeException error) {
            fail(error.toString());
        }
    }

    public static void failed(EntityMaid maid, RuntimeException error) {
        if (matches(maid)) fail(error.toString());
    }

    public static void cancel(String error) {
        if (pending != null && pending.report == null) fail(error);
    }

    public static Result poll() {
        if (pending == null || pending.report == null) return null;
        var result = new Result(pending.request, pending.report);
        pending = null;
        return result;
    }

    private static boolean matches(EntityMaid maid) {
        return pending != null && pending.report == null && pending.maid == maid;
    }

    private static void requireActualModel(BuiltinMaidModel model) {
        if (!model.getChoice()
            .getId()
            .equals(pending.model)) throw new IllegalStateException("Actual model fallback or switch");
    }

    private static void validateActor() {
        requireObserver();
        var minecraft = Minecraft.getMinecraft();
        var maid = pending.maid;
        int maids = 0;
        for (var entity : minecraft.theWorld.loadedEntityList) if (entity instanceof EntityMaid) maids++;
        if (maid.worldObj != minecraft.theWorld || minecraft.theWorld.getEntityByID(maid.getEntityId()) != maid
            || !maid.isEntityAlive()
            || !maid.isOwner(minecraft.thePlayer)
            || !maid.getModelId()
                .equals(pending.model)
            || maid.ridingEntity != null
            || maid.riddenByEntity != null
            || maids != 1
            || !Pose.capture(maid)
                .finite())
            throw new IllegalStateException("Actual lab actor changed during head binding capture");
    }

    private static void requireObserver() {
        var minecraft = Minecraft.getMinecraft();
        var player = minecraft.thePlayer;
        if (!Boolean.getBoolean("tlmnh.devCommands") || !AnimationLab.isLab(minecraft.theWorld)
            || player == null
            || minecraft.currentScreen != null
            || minecraft.playerController == null
            || !player.isEntityAlive()
            || !player.capabilities.disableDamage
            || !player.capabilities.allowFlying
            || !player.capabilities.isFlying
            || !player.noClip
            || player.capabilities.isCreativeMode)
            throw new IllegalStateException("Unobscured safe lab spectator required");
        GameType mode = ReflectionHelper
            .getPrivateValue(PlayerControllerMP.class, minecraft.playerController, "currentGameType", "field_78779_k");
        if (mode == null || mode.getID() != 3) throw new IllegalStateException("Actual RC2 spectator mode 3 required");
    }

    private static JsonObject header() {
        var report = new JsonObject();
        report.addProperty("schema", "head-binding-v1");
        report.addProperty("scope", "actual client actor to native RendererLivingEntity to model/query binding only");
        report.addProperty("renderingVerified", false);
        report.addProperty("upstreamEquivalenceVerified", false);
        report.addProperty("model", pending.model);
        report.addProperty(
            "entityUuid",
            pending.maid.getUniqueID()
                .toString());
        report.addProperty("faultInjection", pending.fault);
        var requested = new JsonObject();
        requested.addProperty("bodyYaw", 180);
        requested.addProperty("headYaw", pending.yaw);
        requested.addProperty("headPitch", pending.pitch);
        requested.addProperty("angleUnit", "degrees");
        report.add("requested", requested);
        report.add("actorBefore", pending.before.json());
        report.addProperty("waitTicks", pending.ticks);
        return report;
    }

    private static void fail(String error) {
        var report = header();
        report.addProperty("status", "failed");
        report.addProperty("error", error);
        report.addProperty("bindingMatched", false);
        finish(report);
    }

    private static void finish(JsonObject report) {
        try {
            pending.before.apply(pending.maid);
        } finally {
            var restored = Pose.capture(pending.maid);
            boolean exact = pending.before.same(restored);
            report.add("actorAfterRestore", restored.json());
            report.addProperty("actorRestored", exact);
            if (!exact) {
                report.addProperty("status", "failed");
                report.addProperty("bindingMatched", false);
            }
            pending.report = report;
        }
    }

    public static final class Result {

        public final String request;
        public final JsonObject report;

        private Result(String request, JsonObject report) {
            this.request = request;
            this.report = report;
        }
    }

    private static final class Request {

        final String request, model, fault;
        final float yaw, pitch;
        final EntityMaid maid;
        final Pose before, expected;
        final long deadline = System.nanoTime() + 10_000_000_000L;
        int ticks;
        JsonObject report;

        Request(String request, String model, float yaw, float pitch, String fault, EntityMaid maid) {
            this.request = request;
            this.model = model;
            this.yaw = yaw;
            this.pitch = pitch;
            this.fault = fault;
            this.maid = maid;
            before = Pose.capture(maid);
            expected = new Pose(180, 180, 180, 180, 180 + yaw, 180 + yaw, pitch, pitch);
        }
    }

    private static final class Pose {

        final float yaw, previousYaw, body, previousBody, head, previousHead, pitch, previousPitch;

        Pose(float yaw, float previousYaw, float body, float previousBody, float head, float previousHead, float pitch,
            float previousPitch) {
            this.yaw = yaw;
            this.previousYaw = previousYaw;
            this.body = body;
            this.previousBody = previousBody;
            this.head = head;
            this.previousHead = previousHead;
            this.pitch = pitch;
            this.previousPitch = previousPitch;
        }

        static Pose capture(EntityMaid maid) {
            return new Pose(
                maid.rotationYaw,
                maid.prevRotationYaw,
                maid.renderYawOffset,
                maid.prevRenderYawOffset,
                maid.rotationYawHead,
                maid.prevRotationYawHead,
                maid.rotationPitch,
                maid.prevRotationPitch);
        }

        void apply(EntityMaid maid) {
            maid.rotationYaw = yaw;
            maid.prevRotationYaw = previousYaw;
            maid.renderYawOffset = body;
            maid.prevRenderYawOffset = previousBody;
            maid.rotationYawHead = head;
            maid.prevRotationYawHead = previousHead;
            maid.rotationPitch = pitch;
            maid.prevRotationPitch = previousPitch;
        }

        boolean same(Pose other) {
            return yaw == other.yaw && previousYaw == other.previousYaw
                && body == other.body
                && previousBody == other.previousBody
                && head == other.head
                && previousHead == other.previousHead
                && pitch == other.pitch
                && previousPitch == other.previousPitch;
        }

        boolean finite() {
            return Float.isFinite(yaw) && Float.isFinite(previousYaw)
                && Float.isFinite(body)
                && Float.isFinite(previousBody)
                && Float.isFinite(head)
                && Float.isFinite(previousHead)
                && Float.isFinite(pitch)
                && Float.isFinite(previousPitch);
        }

        JsonObject json() {
            var value = new JsonObject();
            value.addProperty("yaw", yaw);
            value.addProperty("previousYaw", previousYaw);
            value.addProperty("bodyYaw", body);
            value.addProperty("previousBodyYaw", previousBody);
            value.addProperty("headYaw", head);
            value.addProperty("previousHeadYaw", previousHead);
            value.addProperty("headPitch", pitch);
            value.addProperty("previousHeadPitch", previousPitch);
            return value;
        }
    }
}
