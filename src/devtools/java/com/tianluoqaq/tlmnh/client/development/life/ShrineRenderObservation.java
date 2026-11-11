package com.tianluoqaq.tlmnh.client.development.life;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineTile;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 实际神龛提交与资源对象身份，只读观察，不从draw计数推断像素或库存交付 */
@SideOnly(Side.CLIENT)
public final class ShrineRenderObservation {

    private static final Map<MaidShrineTile, Frame> FRAMES = new WeakHashMap<>();

    private ShrineRenderObservation() {}

    public static void completed(MaidShrineTile shrine, Object model, boolean itemDrawn) {
        if (!DevelopmentFlags.isDevCommandsEnabled()) return;
        var frame = FRAMES.computeIfAbsent(shrine, ignored -> new Frame());
        frame.calls++;
        if (frame.model != model) {
            frame.model = model;
            frame.changes++;
        }
        frame.itemDrawn = itemDrawn;
    }

    public static JsonArray snapshot(Minecraft minecraft) {
        var result = new JsonArray();
        if (!DevelopmentFlags.isDevCommandsEnabled() || minecraft.theWorld == null) return result;
        // loadedTileEntityList只登记canUpdate=true；神龛不tick，沿实际绘制记录核当前世界的同一Tile
        for (var shrine : FRAMES.keySet()) if (!shrine.isInvalid() && shrine.getWorldObj() == minecraft.theWorld
            && minecraft.theWorld.blockExists(shrine.xCoord, shrine.yCoord, shrine.zCoord)
            && minecraft.theWorld.getTileEntity(shrine.xCoord, shrine.yCoord, shrine.zCoord) == shrine) {
                var value = new JsonObject();
                value.addProperty("x", shrine.xCoord);
                value.addProperty("y", shrine.yCoord);
                value.addProperty("z", shrine.zCoord);
                value.addProperty(
                    "metadata",
                    shrine.getWorldObj()
                        .getBlockMetadata(shrine.xCoord, shrine.yCoord, shrine.zCoord));
                var frame = FRAMES.get(shrine);
                if (frame != null) {
                    value.addProperty("nativeDrawReturns", frame.calls);
                    value.addProperty("modelChanges", frame.changes);
                    value.addProperty("itemDrawn", frame.itemDrawn);
                }
                result.add(value);
                if (result.size() >= 32) break;
            }
        return result;
    }

    private static final class Frame {

        Object model;
        long calls, changes;
        boolean itemDrawn;
    }
}
