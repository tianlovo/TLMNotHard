package com.tianluoqaq.tlmnh.client.development.lifecycle;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 只记录实际渲染正常返回及实际模型对象更换，不向业务实体写数据 */
@SideOnly(Side.CLIENT)
public final class TombstoneRenderObservation {

    private static final Map<MaidTombstoneEntity, Frame> FRAMES = new WeakHashMap<>();

    private TombstoneRenderObservation() {}

    public static void completed(MaidTombstoneEntity tombstone, Object model, ResourceLocation texture, int nameLines) {
        if (!Boolean.getBoolean("tlmnh.devCommands")) return;
        var frame = FRAMES.computeIfAbsent(tombstone, ignored -> new Frame());
        if (frame.model != model) {
            frame.model = model;
            frame.modelChanges++;
        }
        frame.calls++;
        frame.texture = texture.toString();
        frame.nameLines = nameLines;
    }

    public static JsonArray snapshot(Minecraft minecraft) {
        var result = new JsonArray();
        if (minecraft.theWorld == null) return result;
        for (Object value : minecraft.theWorld.loadedEntityList) {
            if (!(value instanceof MaidTombstoneEntity tombstone)) continue;
            if (result.size() == 32) break;
            var item = new JsonObject();
            item.addProperty("entityId", tombstone.getEntityId());
            item.addProperty("maidName", tombstone.getMaidName());
            var frame = FRAMES.get(tombstone);
            if (frame != null) {
                item.addProperty("nativeDrawReturns", frame.calls);
                item.addProperty("modelChanges", frame.modelChanges);
                item.addProperty("nameLines", frame.nameLines);
                item.addProperty("texture", frame.texture);
            }
            result.add(item);
        }
        return result;
    }

    private static final class Frame {

        private Object model;
        private int calls, modelChanges, nameLines;
        private String texture;
    }
}
