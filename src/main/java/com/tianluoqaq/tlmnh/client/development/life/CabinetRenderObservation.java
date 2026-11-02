package com.tianluoqaq.tlmnh.client.development.life;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.life.TileEntitySnackCabinet;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 实际柜体提交与资源对象身份，只读观察，不从draw计数推断像素或库存交付 */
@SideOnly(Side.CLIENT)
public final class CabinetRenderObservation {

    private static final Map<TileEntitySnackCabinet, Frame> FRAMES = new WeakHashMap<>();

    private CabinetRenderObservation() {}

    public static void completed(TileEntitySnackCabinet cabinet, Object model, int type, boolean full, boolean half) {
        if (!Boolean.getBoolean("tlmnh.devCommands")) return;
        var frame = FRAMES.computeIfAbsent(cabinet, ignored -> new Frame());
        frame.calls++;
        if (frame.model != model) {
            frame.model = model;
            frame.changes++;
        }
        frame.type = type;
        frame.full = full;
        frame.half = half;
    }

    public static JsonArray snapshot(Minecraft minecraft) {
        var result = new JsonArray();
        if (!Boolean.getBoolean("tlmnh.devCommands") || minecraft.theWorld == null) return result;
        for (Object tile : minecraft.theWorld.loadedTileEntityList)
            if (tile instanceof TileEntitySnackCabinet cabinet && !cabinet.isInvalid()) {
                var value = new JsonObject();
                value.addProperty("x", cabinet.xCoord);
                value.addProperty("y", cabinet.yCoord);
                value.addProperty("z", cabinet.zCoord);
                value.addProperty(
                    "metadata",
                    cabinet.getWorldObj()
                        .getBlockMetadata(cabinet.xCoord, cabinet.yCoord, cabinet.zCoord));
                var frame = FRAMES.get(cabinet);
                if (frame != null) {
                    value.addProperty("nativeDrawReturns", frame.calls);
                    value.addProperty("modelChanges", frame.changes);
                    value.addProperty("type", frame.type);
                    value.addProperty("fullVisible", frame.full);
                    value.addProperty("halfVisible", frame.half);
                }
                result.add(value);
                if (result.size() >= 32) break;
            }
        return result;
    }

    private static final class Frame {

        Object model;
        long calls, changes;
        int type;
        boolean full, half;
    }
}
