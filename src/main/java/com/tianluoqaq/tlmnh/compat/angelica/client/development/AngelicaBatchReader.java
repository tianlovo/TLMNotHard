package com.tianluoqaq.tlmnh.compat.angelica.client.development;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.google.gson.JsonObject;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 只读实际Angelica批次与计数器，不改变队列状态 */
@SideOnly(Side.CLIENT)
public final class AngelicaBatchReader {

    public final Class<?> batcherType, instancerType, glsmType;
    final Object batcher;
    final Method active, queued, held;
    final Method[] batchCounters, itemCounters;
    final Field drawCalls;

    public AngelicaBatchReader() throws Exception {
        batcherType = Class.forName("com.gtnewhorizons.angelica.rendering.tesr.ModelPartBatcher");
        instancerType = Class.forName("com.gtnewhorizons.angelica.rendering.items.DroppedItemInstancer");
        glsmType = Class.forName("com.gtnewhorizons.angelica.glsm.GLStateManager");
        batcher = batcherType.getField("INSTANCE")
            .get(null);
        active = batcherType.getMethod("isActive");
        queued = batcherType.getDeclaredMethod("hasQueuedGeometry");
        held = batcherType.getDeclaredMethod("hasHeldEntities");
        queued.setAccessible(true);
        held.setAccessible(true);
        batchCounters = new Method[] { batcherType.getMethod("statParts"), batcherType.getMethod("statInstancedDraws"),
            batcherType.getMethod("statInstancedInstances"), batcherType.getMethod("statRedrawnInstances") };
        itemCounters = new Method[] { instancerType.getMethod("statInstanced"),
            instancerType.getMethod("statGlintInstanced"), instancerType.getMethod("statFallback") };
        Class<?> draws;
        try {
            draws = Class.forName("com.gtnewhorizons.angelica.glsm.GLSMDraws");
        } catch (ClassNotFoundException legacy) {
            draws = glsmType;
        }
        drawCalls = draws.getField("drawCalls");
    }

    public JsonObject snapshot() {
        var state = new JsonObject();
        state.addProperty("rawQueueSizesKnown", false);
        try {
            state.addProperty("active", (Boolean) active.invoke(batcher));
            state.addProperty("queuedGeometry", (Boolean) queued.invoke(batcher));
            state.addProperty("heldEntities", (Boolean) held.invoke(batcher));
            var batch = new JsonObject();
            for (Method method : batchCounters) batch.addProperty(method.getName(), (Long) method.invoke(batcher));
            state.add("batcherCounters", batch);
            var items = new JsonObject();
            for (Method method : itemCounters) items.addProperty(method.getName(), (Long) method.invoke(null));
            state.add("itemInstancerCounters", items);
            state.addProperty("glsmDrawCalls", drawCalls.getLong(null));
            state.addProperty("known", true);
        } catch (Exception | LinkageError failure) {
            state.addProperty("known", false);
            state.addProperty("readError", failure.toString());
        }
        return state;
    }
}
