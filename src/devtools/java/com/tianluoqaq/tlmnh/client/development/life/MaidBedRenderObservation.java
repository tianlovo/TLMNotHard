package com.tianluoqaq.tlmnh.client.development.life;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.MinecraftForgeClient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.life.bed.TileEntityMaidBed;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 只读真实床Tile颜色和原生draw返回；模型引用计数不能替代可见像素 */
@SideOnly(Side.CLIENT)
public final class MaidBedRenderObservation {

    private static final Map<TileEntityMaidBed, Frame> FRAMES = new WeakHashMap<>();

    private MaidBedRenderObservation() {}

    public static void completed(TileEntityMaidBed bed, Object model, ResourceLocation texture) {
        if (!DevelopmentFlags.isDevCommandsEnabled()) return;
        var frame = FRAMES.computeIfAbsent(bed, ignored -> new Frame());
        if (frame.model != model) {
            frame.model = model;
            frame.modelChanges++;
        }
        frame.calls++;
        frame.texture = texture.toString();
        frame.pass = MinecraftForgeClient.getRenderPass();
    }

    public static JsonArray snapshot(Minecraft minecraft) {
        var output = new JsonArray();
        if (minecraft.theWorld == null) return output;
        for (Object raw : minecraft.theWorld.loadedTileEntityList) {
            if (!(raw instanceof TileEntityMaidBed bed) || bed.isInvalid()) continue;
            if (output.size() == 32) break;
            var value = new JsonObject();
            value.addProperty("x", bed.xCoord);
            value.addProperty("y", bed.yCoord);
            value.addProperty("z", bed.zCoord);
            value.addProperty("color", bed.getColor() == null ? null : bed.getColor().resourceName);
            value.addProperty(
                "metadata",
                bed.getWorldObj()
                    .getBlockMetadata(bed.xCoord, bed.yCoord, bed.zCoord));
            var frame = FRAMES.get(bed);
            if (frame != null) {
                value.addProperty("nativeDrawReturns", frame.calls);
                value.addProperty("modelChanges", frame.modelChanges);
                value.addProperty("texture", frame.texture);
                value.addProperty("lastRenderPass", frame.pass);
            }
            output.add(value);
        }
        return output;
    }

    private static final class Frame {

        Object model;
        int calls, modelChanges, pass;
        String texture;
    }
}
