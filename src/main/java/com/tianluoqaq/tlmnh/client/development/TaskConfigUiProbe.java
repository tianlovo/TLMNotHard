package com.tianluoqaq.tlmnh.client.development;

import java.lang.reflect.Method;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.task.AttackTaskConfigGui;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.task.TaskConfigContainer;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.compat.lwjgl3ify.development.input.LwjglTextInput;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 只驱动实际文本框与GuiScreen鼠标分发，不直接修改攻击分类或调用服务端提交 */
@SideOnly(Side.CLIENT)
public final class TaskConfigUiProbe {

    private TaskConfigUiProbe() {}

    private static Method nativeMethod(String mcp, String srg, Class<?>... parameters) {
        for (String name : new String[] { mcp, srg }) try {
            var method = GuiScreen.class.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException missing) {
            // 同一固定MC方法在开发和正式运行环境使用不同映射名
        }
        throw new IllegalStateException("Native GUI method is unavailable: " + mcp);
    }

    private static void click(AttackTaskConfigGui gui, int x, int y) throws ReflectiveOperationException {
        nativeMethod("mouseClicked", "func_73864_a", int.class, int.class, int.class).invoke(gui, x, y, 0);
        nativeMethod("mouseMovedOrUp", "func_146286_b", int.class, int.class, int.class).invoke(gui, x, y, 0);
    }

    public static boolean perform(AttackTaskConfigGui gui, String action, String value) {
        if (!Boolean.getBoolean("tlmnh.devCommands"))
            throw new IllegalStateException("Development control is disabled");
        int left = (gui.width - 256) / 2, top = (gui.height - 256) / 2;
        try {
            if (action.equals("target_add")) {
                if (value == null || value.length() > 256)
                    throw new IllegalArgumentException("Target input is too long");
                click(gui, left + 100, top + 44);
                var field = AttackTaskConfigGui.class.getDeclaredField("input");
                field.setAccessible(true);
                var input = (GuiTextField) field.get(gui);
                input.setText("");
                var key = nativeMethod("keyTyped", "func_73869_a", char.class, int.class);
                for (char c : value.toCharArray()) {
                    LwjglTextInput.character(c);
                    key.invoke(gui, c, 0);
                }
                click(gui, left + 225, top + 44);
                return true;
            }
            if (action.equals("target_page_next") || action.equals("target_page_previous")) {
                click(gui, left + (action.endsWith("next") ? 245 : 210), top + 60);
                return true;
            }
            var keys = AttackTaskConfigGui.class.getDeclaredField("keys");
            keys.setAccessible(true);
            var page = AttackTaskConfigGui.class.getDeclaredField("page");
            page.setAccessible(true);
            var ids = (java.util.List<?>) keys.get(gui);
            int index = ids.indexOf(new ResourceLocation(value)), currentPage = page.getInt(gui);
            if (index < 0 || index / 7 != currentPage) return false;
            int x = action.equals("target_remove") ? 200
                : action.equals("target_previous") ? 211 : action.equals("target_next") ? 244 : -1;
            if (x < 0) throw new IllegalArgumentException("Unknown target editor action");
            click(gui, left + x, top + 72 + 13 * (index % 7));
            return true;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Native task GUI input failed", error);
        }
    }

    public static JsonObject snapshot(TaskConfigContainer menu) {
        var state = new JsonObject();
        state.addProperty("ready", menu.ready());
        state.addProperty("editable", menu.editable());
        state.addProperty("dirty", menu.dirty());
        state.addProperty("attackPage", menu.attackPage());
        state.addProperty(
            "openedTask",
            menu.openedTask()
                .name());
        state.addProperty("session", Long.toString(menu.session()));
        var groups = new JsonObject();
        menu.editedGroups()
            .forEach((id, type) -> groups.addProperty(id.toString(), type.name()));
        state.add("groups", groups);
        state.addProperty("clientReplicaOnly", true);
        return state;
    }
}
