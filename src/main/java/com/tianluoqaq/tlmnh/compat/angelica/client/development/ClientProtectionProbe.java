package com.tianluoqaq.tlmnh.compat.angelica.client.development;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.client.render.MaidRenderProtection;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.common.ObfuscationReflectionHelper;
import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.Side;

/** 显式开发入口：隔离外部类查找，读取真实GL状态与客户端聊天记录 */
public final class ClientProtectionProbe {

    private static String injectedCode;
    private static int lookups;

    private ClientProtectionProbe() {}

    public static JsonObject perform(String action) throws ReflectiveOperationException {
        if (!Boolean.getBoolean("tlmnh.devCommands") || Minecraft.getMinecraft().thePlayer == null)
            throw new IllegalStateException("Development client player required");
        var result = new JsonObject();
        if (action.equals("missing_api")) {
            if (!IntegrationGuard.faults(Side.CLIENT)
                .isEmpty() || !MaidRenderProtection.ready())
                throw new IllegalStateException("Clean client protection state required");
            var type = Class.forName("com.tianluoqaq.tlmnh.compat.angelica.client.render.MaidRenderPlatformState");
            var loader = type.getDeclaredField("providerLoader");
            var bindings = type.getDeclaredField("bindings");
            loader.setAccessible(true);
            bindings.setAccessible(true);
            Object oldLoader = loader.get(null), oldBindings = bindings.get(null);
            int[] before = glState();
            var calls = new AtomicInteger();
            lookups = 0;
            try {
                loader.set(null, new ClassLoader((ClassLoader) oldLoader) {

                    @Override
                    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                        if (name.equals("com.gtnewhorizons.angelica.glsm.GLStateManager")) {
                            lookups++;
                            throw new ClassNotFoundException("QA isolated missing Angelica API");
                        }
                        return super.loadClass(name, resolve);
                    }
                });
                bindings.set(null, null);
                MaidRenderProtection.draw(calls::incrementAndGet);
                MaidRenderProtection.draw(calls::incrementAndGet);
                var fault = IntegrationGuard.faults(Side.CLIENT)
                    .get(ExternalFeature.CLIENT_RENDER_STATE.id);
                if (fault == null || calls.get() != 0 || lookups != 1 || !Arrays.equals(before, glState()))
                    throw new IllegalStateException("Failed render preflight mutated GL or retried missing API");
                injectedCode = fault.code;
                result.addProperty("preflightNoWrites", true);
                result.addProperty("drawCalls", calls.get());
            } finally {
                loader.set(null, oldLoader);
                bindings.set(null, oldBindings);
            }
        } else if (action.equals("recover")) {
            var faults = IntegrationGuard.faults(Side.CLIENT);
            if (faults.size() != 1 || injectedCode == null
                || !faults.get(ExternalFeature.CLIENT_RENDER_STATE.id).code.equals(injectedCode))
                throw new IllegalStateException("Only the isolated probe fault may be cleared");
            IntegrationGuard.resetForDevelopment(Side.CLIENT);
        }
        result.addProperty("renderReady", MaidRenderProtection.ready());
        result.addProperty("lookups", lookups);
        result.addProperty("injectedCode", injectedCode);
        result.addProperty(
            "clientFaults",
            IntegrationGuard.faults(Side.CLIENT)
                .size());
        result.addProperty(
            "serverFaults",
            IntegrationGuard.faults(Side.SERVER)
                .size());
        result.addProperty("serverShieldsAvailable", IntegrationGuard.available(Side.SERVER, ExternalFeature.SHIELDS));
        var chat = Minecraft.getMinecraft().ingameGUI.getChatGUI();
        var field = ReflectionHelper.findField(
            GuiNewChat.class,
            ObfuscationReflectionHelper.remapFieldNames(GuiNewChat.class.getName(), "field_146252_h"));
        var messages = new JsonArray();
        for (Object value : (List<?>) field.get(chat)) {
            var text = ((ChatLine) value).func_151461_a()
                .getUnformattedText();
            if (injectedCode != null && text.contains(injectedCode)
                && text.contains(ExternalFeature.CLIENT_RENDER_STATE.id))
                messages.add(new com.google.gson.JsonPrimitive(text));
        }
        result.add("localWarnings", messages);
        return result;
    }

    private static int[] glState() {
        return new int[] { GL11.glGetInteger(GL11.GL_MATRIX_MODE), GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),
            GL11.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH), GL11.glGetInteger(GL11.GL_CLIENT_ATTRIB_STACK_DEPTH),
            GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH), GL11.glGetInteger(GL11.GL_PROJECTION_STACK_DEPTH),
            GL11.glGetInteger(GL11.GL_TEXTURE_STACK_DEPTH) };
    }
}
