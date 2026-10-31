/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/gui/entity/cache/CacheIconManager.java, MIT
 * Adaptation: fixed builtin indices, authoritative window and explicit resource invalidation
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.entity.cache;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.model.MaidModelGui;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.texture.CacheIconTexture;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.client.gui.MaidClientPreferences;
import com.tianluoqaq.tlmnh.client.render.MaidIconCapture;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidModels;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class CacheIconManager implements IResourceManagerReloadListener {

    public static final CacheIconManager INSTANCE = new CacheIconManager();
    private final Map<Integer, CacheIconTexture> textures = new HashMap<>();
    private final Queue<Integer> queue = new ArrayDeque<>();
    private int generation, failures, lastInvalidated, lastDeleteFailures, lastRetainedTextureNames;

    private CacheIconManager() {}

    public GuiScreen openMaidModelGui(ContainerMaidModels container) {
        var parent = new MaidModelGui(container);
        return MaidClientPreferences.modelIconCache() && !queue.isEmpty() ? new CacheScreen(parent) : parent;
    }

    public int remaining() {
        return queue.size();
    }

    public static ResourceLocation location(int index) {
        return new ResourceLocation("tlmnh", "cache/builtin/" + index);
    }

    public boolean draw(int index, int x, int y) {
        if (!MaidClientPreferences.modelIconCache() || !textures.containsKey(index)) return false;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        try {
            Minecraft.getMinecraft()
                .getTextureManager()
                .bindTexture(location(index));
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GuiScreen.func_146110_a(x - 12, y - 24, 0, 0, 24, 24, 24, 24);
        } finally {
            GL11.glPopAttrib();
        }
        return true;
    }

    void cacheNext(EntityMaid preview) {
        Integer index = queue.poll();
        if (index == null) return;
        var choice = BuiltinModelCatalog.choices()
            .get(index);
        preview.setModelId(choice.getId());
        try {
            textures.put(index, MaidIconCapture.capture(preview, choice.getItemScale(), location(index)));
        } catch (RuntimeException failure) {
            failures++;
            TLMNotHard.LOG.error("Cannot cache builtin model icon {}", choice.getId(), failure);
        }
    }

    @Override
    public void onResourceManagerReload(IResourceManager manager) {
        lastInvalidated = textures.size();
        lastDeleteFailures = 0;
        lastRetainedTextureNames = 0;
        for (var texture : textures.values()) {
            int id = texture.getGlTextureId();
            texture.deleteGlTexture();
            if (!texture.isReleased()) lastDeleteFailures++;
            // Angelica first frees image storage and retains names until its next allocation
            if (GL11.glIsTexture(id)) lastRetainedTextureNames++;
        }
        textures.clear();
        queue.clear();
        failures = 0;
        for (int i = 0; i < BuiltinModelCatalog.choices()
            .size(); i++) queue.add(i);
        generation++;
        MaidIconCapture.releaseScratch();
    }

    public JsonObject observation() {
        var result = new JsonObject();
        result.addProperty("enabled", MaidClientPreferences.modelIconCache());
        result.addProperty("generation", generation);
        result.addProperty("remaining", queue.size());
        result.addProperty("ready", textures.size());
        result.addProperty("failures", failures);
        result.addProperty("lastInvalidated", lastInvalidated);
        result.addProperty("lastDeleteFailures", lastDeleteFailures);
        result.addProperty("lastRetainedTextureNames", lastRetainedTextureNames);
        int winefox = 0, empty = 0;
        for (var entry : textures.entrySet()) {
            if (BuiltinModelCatalog.choices()
                .get(entry.getKey())
                .isWinefox()) winefox++;
            if (entry.getValue()
                .visiblePixels() == 0) empty++;
        }
        result.addProperty("winefoxReady", winefox);
        result.addProperty("emptyIcons", empty);
        return result;
    }
}
