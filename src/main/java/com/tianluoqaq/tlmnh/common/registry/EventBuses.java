package com.tianluoqaq.tlmnh.common.registry;

import java.util.Objects;

import net.minecraftforge.common.MinecraftForge;

import cpw.mods.fml.common.FMLCommonHandler;

/** Forge 与 FML 事件总线的注册小助手，省去需要双总线监听时的成对样板代码 */
public final class EventBuses {

    private EventBuses() {}

    public static void registerForge(Object listener) {
        Objects.requireNonNull(listener, "listener");
        MinecraftForge.EVENT_BUS.register(listener);
    }

    public static void registerFml(Object listener) {
        Objects.requireNonNull(listener, "listener");
        FMLCommonHandler.instance()
            .bus()
            .register(listener);
    }

    public static void registerBoth(Object listener) {
        registerForge(listener);
        registerFml(listener);
    }

    public static void unregisterBoth(Object listener) {
        Objects.requireNonNull(listener, "listener");
        MinecraftForge.EVENT_BUS.unregister(listener);
        FMLCommonHandler.instance()
            .bus()
            .unregister(listener);
    }
}
