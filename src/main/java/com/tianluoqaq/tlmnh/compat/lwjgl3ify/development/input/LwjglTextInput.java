package com.tianluoqaq.tlmnh.compat.lwjgl3ify.development.input;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 开发探针通过实装模组公开的文本事件入口输入，不访问剪贴板或直接修改文本缓冲 */
@SideOnly(Side.CLIENT)
public final class LwjglTextInput {

    private LwjglTextInput() {}

    public static void character(char value) throws ReflectiveOperationException {
        if (!Boolean.getBoolean("tlmnh.devCommands"))
            throw new IllegalStateException("Development control is disabled");
        Class<?> events;
        try {
            events = Class.forName("me.eigenraven.lwjgl3ify.api.InputEvents");
        } catch (ClassNotFoundException absent) {
            return;
        }
        var textEvent = Class.forName("me.eigenraven.lwjgl3ify.api.InputEvents$TextEvent");
        var event = textEvent.getConstructor(String.class)
            .newInstance(Character.toString(value));
        events.getMethod("injectTextEvent", textEvent)
            .invoke(null, event);
    }
}
