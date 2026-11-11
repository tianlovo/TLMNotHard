package com.tianluoqaq.tlmnh.client.development.life;

import java.util.Map;
import java.util.WeakHashMap;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 实际客户端卧姿根变换返回；不设置姿态或由计数推断可见像素 */
@SideOnly(Side.CLIENT)
public final class MaidSleepRenderObservation {

    private static final Map<EntityMaid, Frame> FRAMES = new WeakHashMap<>();

    private MaidSleepRenderObservation() {}

    public static void completed(EntityMaid maid, int direction, float rotation) {
        if (!DevelopmentFlags.isDevCommandsEnabled()) return;
        var frame = FRAMES.computeIfAbsent(maid, ignored -> new Frame());
        frame.calls++;
        frame.direction = direction;
        frame.rotation = rotation;
    }

    public static JsonArray snapshot() {
        var result = new JsonArray();
        if (!DevelopmentFlags.isDevCommandsEnabled()) return result;
        for (var entry : FRAMES.entrySet()) {
            var maid = entry.getKey();
            if (maid.isDead) continue;
            var frame = entry.getValue();
            var value = new JsonObject();
            value.addProperty(
                "uuid",
                maid.getUniqueID()
                    .toString());
            value.addProperty("model", maid.getModelId());
            value.addProperty("sleeping", maid.hasPose(MaidPose.SLEEPING));
            value.addProperty("sitting", maid.isSitting());
            value.addProperty("direction", maid.getSleepingBedDirection());
            value.addProperty("nativeSleepTransforms", frame.calls);
            value.addProperty("lastDirection", frame.direction);
            value.addProperty("lastRotation", frame.rotation);
            value.addProperty("x", maid.posX);
            value.addProperty("y", maid.posY);
            value.addProperty("z", maid.posZ);
            result.add(value);
            if (result.size() >= 32) break;
        }
        return result;
    }

    private static final class Frame {

        long calls;
        int direction;
        float rotation;
    }
}
